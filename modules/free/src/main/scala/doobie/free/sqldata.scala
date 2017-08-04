package doobie.free

import cats.~>
import cats.free.{ Free => FF }
import scala.util.{ Either => \/ }
import fs2.util.{ Catchable, Suspendable }

import java.lang.String
import java.sql.SQLData
import java.sql.SQLInput
import java.sql.SQLOutput

object sqldata {

  // Algebra of operations for SQLData. Each accepts a visitor as an alternatie to pattern-matching.
  sealed trait SQLDataOp[A] {
    def visit[F[_]](v: SQLDataOp.Visitor[F]): F[A]
  }

  // Free monad over SQLDataOp.
  type SQLDataIO[A] = FF[SQLDataOp, A]

  // Module of instances and constructors of SQLDataOp.
  object SQLDataOp {

    // Given a SQLData we can embed a SQLDataIO program in any algebra that understands embedding.
    implicit val SQLDataOpEmbeddable: Embeddable[SQLDataOp, SQLData] =
      new Embeddable[SQLDataOp, SQLData] {
        def embed[A](j: SQLData, fa: FF[SQLDataOp, A]) = Embedded.SQLData(j, fa)
      }

    // Interface for a natural tansformation SQLDataOp ~> F encoded via the visitor pattern.
    // This approach is much more efficient than pattern-matching for large algebras.
    trait Visitor[F[_]] extends (SQLDataOp ~> F) {
      final def apply[A](fa: SQLDataOp[A]): F[A] = fa.visit(this)

      // Common
      def raw[A](f: SQLData => A): F[A]
      def embed[A](e: Embedded[A]): F[A]
      def delay[A](a: () => A): F[A]
      def attempt[A](fa: SQLDataIO[A]): F[Throwable \/ A]

      // SQLData
      def getSQLTypeName: F[String]
      def readSQL(a: SQLInput, b: String): F[Unit]
      def writeSQL(a: SQLOutput): F[Unit]

    }

    // Common operations for all algebras.
    case class Raw[A](f: SQLData => A) extends SQLDataOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.raw(f)
    }
    case class Embed[A](e: Embedded[A]) extends SQLDataOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.embed(e)
    }
    case class  Delay[A](a: () => A) extends SQLDataOp[A] {
      def visit[F[_]](v: Visitor[F]) = v.delay(a)
    }
    case class  Attempt[A](fa: SQLDataIO[A]) extends SQLDataOp[Throwable \/ A] {
      def visit[F[_]](v: Visitor[F]) = v.attempt(fa)
    }

    // SQLData-specific operations.
    case object GetSQLTypeName extends SQLDataOp[String] {
      def visit[F[_]](v: Visitor[F]) = v.getSQLTypeName
    }
    case class  ReadSQL(a: SQLInput, b: String) extends SQLDataOp[Unit] {
      def visit[F[_]](v: Visitor[F]) = v.readSQL(a, b)
    }
    case class  WriteSQL(a: SQLOutput) extends SQLDataOp[Unit] {
      def visit[F[_]](v: Visitor[F]) = v.writeSQL(a)
    }

  }
  import SQLDataOp._

  // Smart constructors for operations common to all algebras.
  val unit: SQLDataIO[Unit] = FF.pure[SQLDataOp, Unit](())
  def raw[A](f: SQLData => A): SQLDataIO[A] = FF.liftF(Raw(f))
  def embed[F[_], J, A](j: J, fa: FF[F, A])(implicit ev: Embeddable[F, J]): FF[SQLDataOp, A] = FF.liftF(Embed(ev.embed(j, fa)))
  def lift[F[_], J, A](j: J, fa: FF[F, A])(implicit ev: Embeddable[F, J]): FF[SQLDataOp, A] = embed(j, fa)
  def delay[A](a: => A): SQLDataIO[A] = FF.liftF(Delay(() => a))
  def attempt[A](fa: SQLDataIO[A]): SQLDataIO[Throwable \/ A] = FF.liftF[SQLDataOp, Throwable \/ A](Attempt(fa))
  def fail[A](err: Throwable): SQLDataIO[A] = delay(throw err)

  // Smart constructors for SQLData-specific operations.
  val getSQLTypeName: SQLDataIO[String] = FF.liftF(GetSQLTypeName)
  def readSQL(a: SQLInput, b: String): SQLDataIO[Unit] = FF.liftF(ReadSQL(a, b))
  def writeSQL(a: SQLOutput): SQLDataIO[Unit] = FF.liftF(WriteSQL(a))

// SQLDataIO can capture side-effects, and can trap and raise exceptions.
  implicit val CatchableSQLDataIO: Suspendable[SQLDataIO] with Catchable[SQLDataIO] =
    new Suspendable[SQLDataIO] with Catchable[SQLDataIO] {
      def pure[A](a: A): SQLDataIO[A] = sqldata.delay(a)
      override def map[A, B](fa: SQLDataIO[A])(f: A => B): SQLDataIO[B] = fa.map(f)
      def flatMap[A, B](fa: SQLDataIO[A])(f: A => SQLDataIO[B]): SQLDataIO[B] = fa.flatMap(f)
      def suspend[A](fa: => SQLDataIO[A]): SQLDataIO[A] = FF.suspend(fa)
      override def delay[A](a: => A): SQLDataIO[A] = sqldata.delay(a)
      def attempt[A](f: SQLDataIO[A]): SQLDataIO[Throwable \/ A] = sqldata.attempt(f)
      def fail[A](err: Throwable): SQLDataIO[A] = sqldata.fail(err)
    }

}
