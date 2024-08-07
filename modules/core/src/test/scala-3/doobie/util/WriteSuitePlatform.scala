// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import Predef.augmentString
import doobie.testutils.*

trait WriteSuitePlatform { self: munit.FunSuite =>

  case class Woozle(a: (String, Int), b: Int *: String *: EmptyTuple, c: Boolean)

  sealed trait NoPutInstanceForThis
  case class CaseClassWithFieldWithoutPutInstance(a: String, b: NoPutInstanceForThis)

  test("Write should exist for some fancy types") {
    import doobie.generic.auto.*

    Write[Woozle].void
    Write[(Woozle, String)].void
    Write[(Int, Woozle *: Woozle *: String *: EmptyTuple)].void
  }

  test("Write should exist for option of some fancy types") {
    import doobie.generic.auto.*

    Write[Option[Woozle]].void
    Write[Option[(Woozle, String)]].void
    Write[Option[(Int, Woozle *: Woozle *: String *: EmptyTuple)]].void
  }

  test("Write should not exist for case class with field without Put instance") {
    assert(compileErrors("Write[CaseClassWithFieldWithoutPutInstance]").contains("Cannot find or construct"))
  }

  test("derives") {
    case class Foo(a: String, b: Int) derives Write
    val _ = Foo("", 1)
  }
}
