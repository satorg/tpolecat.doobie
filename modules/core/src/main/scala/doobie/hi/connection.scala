// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.hi

import cats.Foldable
import cats.data.Ior
import cats.effect.kernel.syntax.monadCancel._
import cats.syntax.all._
import doobie.enumerated.AutoGeneratedKeys
import doobie.enumerated.Holdability
import doobie.enumerated.Nullability
import doobie.enumerated.ResultSetConcurrency
import doobie.enumerated.ResultSetType
import doobie.enumerated.TransactionIsolation
import doobie.util.analysis.Analysis
import doobie.util.analysis.ColumnMeta
import doobie.util.analysis.ParameterMeta
import doobie.util.compat.propertiesToScala
import doobie.util.stream.repeatEvalChunks
import doobie.util.{ Get, Put, Read, Write }
import fs2.Stream
import fs2.Stream.{ eval, bracket }
import doobie.hi.{preparedstatement => IHPS}
import doobie.free.{
  preparedstatement => IFPS, 
  connection => IFC,
  resultset => IFRS, 
  databasemetadata => IFDMD,
  statement => IFS,
  callablestatement => IFCS
}

import java.sql.{ Savepoint, PreparedStatement, ResultSet }
import scala.collection.immutable.Map

/**
 * Module of high-level constructors for `ConnectionIO` actions.
 * @group Modules
 */

object connection {
  import implicits._

  /** @group Lifting */
  def delay[A](a: => A): ConnectionIO[A] =
    IFC.delay(a)

  private def liftStream[A: Read](
    chunkSize: Int,
    create: ConnectionIO[PreparedStatement],
    prep:   PreparedStatementIO[Unit],
    exec:   PreparedStatementIO[ResultSet]): Stream[ConnectionIO, A] = {

    def prepared(ps: PreparedStatement): Stream[ConnectionIO, PreparedStatement] =
      eval[ConnectionIO, PreparedStatement] {
        val fs = IFPS.setFetchSize(chunkSize)
        IFC.embed(ps, fs *> prep).map(_ => ps)
      }

    def unrolled(rs: ResultSet): Stream[ConnectionIO, A] =
      repeatEvalChunks(IFC.embed(rs, resultset.getNextChunk[A](chunkSize)))

    val preparedStatement: Stream[ConnectionIO, PreparedStatement] =
      bracket(create)(IFC.embed(_, IFPS.close)).flatMap(prepared)

    def results(ps: PreparedStatement): Stream[ConnectionIO, A] =
      bracket(IFC.embed(ps, exec))(IFC.embed(_, IFRS.close)).flatMap(unrolled)

    preparedStatement.flatMap(results)

  }

  /**
   * Construct a prepared statement from the given `sql`, configure it with the given `PreparedStatementIO`
   * action, and return results via a `Stream`.
   * @group Prepared Statements
   */
  def stream[A: Read](sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
    liftStream(chunkSize, IFC.prepareStatement(sql), prep, IFPS.executeQuery)

  /**
   * Construct a prepared update statement with the given return columns (and readable destination
   * type `A`) and sql source, configure it with the given `PreparedStatementIO` action, and return
   * the generated key results via a
   * `Stream`.
   * @group Prepared Statements
   */
  def updateWithGeneratedKeys[A: Read](cols: List[String])(sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
    liftStream(chunkSize, IFC.prepareStatement(sql, cols.toArray), prep, IFPS.executeUpdate *> IFPS.getGeneratedKeys)

  /** @group Prepared Statements */
  def updateManyWithGeneratedKeys[F[_]: Foldable, A: Write, B: Read](cols: List[String])(sql: String, prep: PreparedStatementIO[Unit], fa: F[A], chunkSize: Int): Stream[ConnectionIO, B] =
    liftStream[B](chunkSize, IFC.prepareStatement(sql, cols.toArray), prep, IHPS.addBatchesAndExecute(fa) *> IFPS.getGeneratedKeys)

  /** @group Transaction Control */
  val commit: ConnectionIO[Unit] =
    IFC.commit

  /**
   * Construct an analysis for the provided `sql` query, given writable parameter type `A` and
   * readable resultset row type `B`.
   */
  def prepareQueryAnalysis[A: Write, B: Read](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, IHPS.getParameterMappings[A], IHPS.getColumnMappings[B])

  def prepareQueryAnalysis0[B: Read](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, IFPS.pure(Nil), IHPS.getColumnMappings[B])

  def prepareUpdateAnalysis[A: Write](sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, IHPS.getParameterMappings[A], IFPS.pure(Nil))

  def prepareUpdateAnalysis0(sql: String): ConnectionIO[Analysis] =
    prepareAnalysis(sql, IFPS.pure(Nil), IFPS.pure(Nil))

  private def prepareAnalysis(
    sql: String,
    params: PreparedStatementIO[List[(Put[_], Nullability.NullabilityKnown) Ior ParameterMeta]],
    columns: PreparedStatementIO[List[(Get[_], Nullability.NullabilityKnown) Ior ColumnMeta]],
  ) = {
    val mappings = prepareStatement(sql) {
      (params, columns).tupled
    }
    (getMetaData(IFDMD.getDriverName), mappings).mapN { case (driver, (p, c)) =>
      Analysis(driver, sql, p, c)
    }
  }


  /** @group Statements */
  def createStatement[A](k: StatementIO[A]): ConnectionIO[A] =
    IFC.createStatement.bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFS.close))

  /** @group Statements */
  def createStatement[A](rst: ResultSetType, rsc: ResultSetConcurrency)(k: StatementIO[A]): ConnectionIO[A] =
    IFC.createStatement(rst.toInt, rsc.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFS.close))

  /** @group Statements */
  def createStatement[A](rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: StatementIO[A]): ConnectionIO[A] =
    IFC.createStatement(rst.toInt, rsc.toInt, rsh.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFS.close))

  /** @group Connection Properties */
  val getCatalog: ConnectionIO[String] =
    IFC.getCatalog

  /** @group Connection Properties */
  def getClientInfo(key: String): ConnectionIO[Option[String]] =
    IFC.getClientInfo(key).map(Option(_))

  /** @group Connection Properties */
  val getClientInfo: ConnectionIO[Map[String, String]] =
    IFC.getClientInfo.map(propertiesToScala)

  /** @group Connection Properties */
  val getHoldability: ConnectionIO[Holdability] =
    IFC.getHoldability.flatMap(Holdability.fromIntF[ConnectionIO])

  /** @group Connection Properties */
  def getMetaData[A](k: DatabaseMetaDataIO[A]): ConnectionIO[A] =
    IFC.getMetaData.flatMap(s => IFC.embed(s, k))

  /** @group Transaction Control */
  val getTransactionIsolation: ConnectionIO[TransactionIsolation] =
    IFC.getTransactionIsolation.flatMap(TransactionIsolation.fromIntF[ConnectionIO])

  /** @group Connection Properties */
  val isReadOnly: ConnectionIO[Boolean] =
    IFC.isReadOnly

  /** @group Callable Statements */
  def prepareCall[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency)(k: CallableStatementIO[A]): ConnectionIO[A] =
    IFC.prepareCall(sql, rst.toInt, rsc.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFCS.close))

  /** @group Callable Statements */
  def prepareCall[A](sql: String)(k: CallableStatementIO[A]): ConnectionIO[A] =
    IFC.prepareCall(sql).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFCS.close))

  /** @group Callable Statements */
  def prepareCall[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: CallableStatementIO[A]): ConnectionIO[A] =
    IFC.prepareCall(sql, rst.toInt, rsc.toInt, rsh.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFCS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql, rst.toInt, rsc.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql, rst.toInt, rsc.toInt, rsh.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, agk: AutoGeneratedKeys)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql, agk.toInt).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Prepared Statements */
  def prepareStatementI[A](sql: String, columnIndexes: List[Int])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql, columnIndexes.toArray).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Prepared Statements */
  def prepareStatementS[A](sql: String, columnNames: List[String])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    IFC.prepareStatement(sql, columnNames.toArray).bracket(s => IFC.embed(s, k))(s => IFC.embed(s, IFPS.close))

  /** @group Transaction Control */
  def releaseSavepoint(sp: Savepoint): ConnectionIO[Unit] =
    IFC.releaseSavepoint(sp)

  /** @group Transaction Control */
  def rollback(sp: Savepoint): ConnectionIO[Unit] =
    IFC.rollback(sp)

  /** @group Transaction Control */
  val rollback: ConnectionIO[Unit] =
    IFC.rollback

  /** @group Connection Properties */
  def setCatalog(catalog: String): ConnectionIO[Unit] =
    IFC.setCatalog(catalog)

  /** @group Connection Properties */
  def setClientInfo(key: String, value: String): ConnectionIO[Unit] =
    IFC.setClientInfo(key, value)

  /** @group Connection Properties */
  def setClientInfo(info: Map[String, String]): ConnectionIO[Unit] =
    IFC.setClientInfo {
      // Java 11 overloads the `putAll` method with Map[*,*] along with the existing Map[Obj,Obj]
      val ps = new java.util.Properties
      info.foreach { case (k, v) =>
        ps.put(k, v)
      }
      ps
    }

  /** @group Connection Properties */
  def setHoldability(h: Holdability): ConnectionIO[Unit] =
    IFC.setHoldability(h.toInt)

  /** @group Connection Properties */
  def setReadOnly(readOnly: Boolean): ConnectionIO[Unit] =
    IFC.setReadOnly(readOnly)

  /** @group Transaction Control */
  val setSavepoint: ConnectionIO[Savepoint] =
    IFC.setSavepoint

  /** @group Transaction Control */
  def setSavepoint(name: String): ConnectionIO[Savepoint] =
    IFC.setSavepoint(name)

  /** @group Transaction Control */
  def setTransactionIsolation(ti: TransactionIsolation): ConnectionIO[Unit] =
    IFC.setTransactionIsolation(ti.toInt)

  // /**
  //  * Compute a map from native type to closest-matching JDBC type.
  //  * @group MetaData
  //  */
  // val nativeTypeMap: ConnectionIO[Map[String, JdbcType]] = {
  //   getMetaData(IFDMD.getTypeInfo.flatMap(IFDMD.embed(_, HRS.list[(String, JdbcType)].map(_.toMap))))
  // }
}
