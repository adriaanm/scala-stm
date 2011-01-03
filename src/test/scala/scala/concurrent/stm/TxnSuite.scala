/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite


class TxnSuite extends FunSuite {

  test("empty transaction") {
    atomic { implicit t =>
      () // do nothing
    }
  }

  test("atomic function") {
    val answer = atomic { implicit t =>
      42
    }
    assert(Integer.parseInt(answer.toString, 13) === 6*9)
  }

  test("large transaction") {
    val refs = Array.tabulate(10000) { i => Ref(i) }
    atomic { implicit txn =>
      for (r <- refs)
        r() = r() + 1
    }
  }

  test("duplicate view with old access") {
    val x = Ref(1)
    atomic { implicit t =>
      val b1 = x.single
      assert(b1.get === 1)
      val b2 = x.single
      assert(b2.get === 1)
      b1() = 2
      assert(b1.get === 2)
      assert(b2.get === 2)
      b2() = 3
      assert(b1.get === 3)
      assert(b2.get === 3)
    }
    assert(x.single.get === 3)
  }

  class UserException extends Exception

  test("failure atomicity") {
    val x = Ref(1)
    intercept[UserException] {
      atomic { implicit t =>
        x() = 2
        throw new UserException
      }
    }
    assert(x.single.get === 1)
  }

  test("non-local return") {
    val x = Ref(1)
    val y = nonLocalReturnHelper(x)
    assert(x.single.get === 2)
    assert(y === 2)
  }

  def nonLocalReturnHelper(x: Ref[Int]): Int = {
    atomic { implicit t =>
      x() = x() + 1
      return x()
    }
    return -1
  }

  test("basic retry") {
    val x = Ref(0)
    val y = Ref(false)
    new Thread {
      override def run() {
        Thread.sleep(200)
        y.single() = true
        x.single() = 1
      }
    } start

    atomic { implicit txn =>
      if (x() == 0)
        retry
    }
    assert(y.single())
  }

  test("nested retry") {
    val x = Ref(0)
    val y = Ref(false)
    new Thread {
      override def run() {
        Thread.sleep(200)
        y.single() = true
        x.single() = 1
      }
    } start

    atomic { implicit txn =>
      atomic { implicit txn =>
        // this will cause the nesting to materialize
        NestingLevel.current

        if (x() == 0)
          retry
      }
    }
    assert(y.single())
  }

  test("simple orAtomic") {
    val x = Ref(0)
    val f = atomic { implicit txn =>
      if (x() == 0)
        retry
      false
    } orAtomic { implicit txn =>
      true
    }
    assert(f)    
  }

  test("single atomic.oneOf") {
    val x = Ref("zero")
    atomic.oneOf({ implicit txn =>
      x() = "one"
    })
    assert(x.single() === "one")
  }

  test("atomic.oneOf") {
    val refs = Array(Ref(false), Ref(false), Ref(false))
    for (w <- 0 until 3) {
      new Thread("wakeup") {
        override def run {
          Thread.sleep(200)
          refs(w).single() = true
        }
      }.start()
      oneOfExpect(refs, w, Array(0))
    }
  }

  test("nested atomic.oneOf") {
    val refs = Array(Ref(false), Ref(false), Ref(false))
    for (w <- 0 until 3) {
      new Thread("wakeup") {
        override def run {
          Thread.sleep(200)
          refs(w).single() = true
        }
      }.start()
      val retries = Array(0)
      atomic { implicit txn => oneOfExpect(refs, w, retries) }
    }
  }

  test("alternative atomic.oneOf") {
    val a = Ref(0)
    val refs = Array(Ref(false), Ref(false), Ref(false))
    for (w <- 0 until 3) {
      new Thread("wakeup") {
        override def run {
          Thread.sleep(200)
          refs(w).single() = true
        }
      }.start()
      val retries = Array(0)
      val f = atomic { implicit txn =>
        if (a() == 0)
          retry
        false
      } orAtomic { implicit txn =>
        oneOfExpect(refs, w, retries)
        true
      }
      assert(f)
    }
  }

  private def oneOfExpect(refs: Array[Ref[Boolean]], which: Int, sleeps: Array[Int]) {
    val result = Ref(-1)
    atomic.oneOf(
        { t: InTxn => implicit val txn = t; result() = 0 ; if (!refs(0)()) retry },
        { t: InTxn => implicit val txn = t; if (refs(1)()) result() = 1 else retry },
        { t: InTxn => implicit val txn = t; if (refs(2)()) result() = 2 else retry },
        { t: InTxn => implicit val txn = t; sleeps(0) += 1 ; retry }
      )
    refs(which).single() = false
    assert(result.single.get === which)
    if (sleeps(0) != 0)
      assert(sleeps(0) === 1)
  }

  test("orAtomic w/ exception") {
    intercept[UserException] {
      atomic { implicit t =>
        if ("likely".hashCode != 0)
          retry
      } orAtomic { implicit t =>
        throw new UserException
      }
    }
  }

  test("Atomic.orAtomic") {
    val x = Ref(1)
    def a() = {
      atomic { implicit t =>
        if (x() > 1) true else retry
      } orAtomic { implicit t =>
        false
      }
    }
    assert(a() === false)
    x.single() = 2
    assert(a() === true)
  }

  test("simple nesting") {
    val x = Ref(10)
    atomic { implicit t =>
      x += 1
      atomic { implicit t =>
        assert(x.get === 11)
        x += 2
      }
      assert(x.get === 13)
    }
    assert(x.single.get === 13)
  }

  test("partial rollback") {
    val x = Ref("none")
    atomic { implicit t =>
      x() = "outer"
      try {
        atomic { implicit t =>
          x() = "inner"
          throw new UserException
        }
      } catch {
        case _: UserException =>
      }
    }
    assert(x.single() === "outer")
  }

  test("partial rollback of transform") {
    val x = Ref("none")
    atomic { implicit t =>
      x() = "outer"
      try {
        atomic { implicit t =>
          x.transform { _ + "inner" }
          throw new UserException
        }
      } catch {
        case _: UserException =>
      }
    }
    assert(x.single() === "outer")
  }

  test("partial rollback due to invalid read") {
    val x = Ref(0)
    val y = Ref(0)

    (new Thread { override def run() { Thread.sleep(100) ; y.single() = 1 } }).start

    atomic { implicit t =>
      x()
      atomic { implicit t =>
        y()
        Thread.sleep(200)
        y()
      } orAtomic { implicit t =>
        throw new Error("should not be run")
      }
    } orAtomic { implicit t =>
      throw new Error("should not be run")
    }
  }

  test("retry set accumulation across alternatives") {
    val x = Ref(false)

    // this prevents the test from deadlocking
    new Thread("trigger") {
      override def run {
        Thread.sleep(200)
        x.single() = true
      }
    } start

    atomic { implicit t =>
      // The following txn and its alternative decode the value of x that was
      // observed, without x being a part of the current read set.
      val f = atomic { implicit t =>
        atomic { implicit t =>
          // this txn encodes the read of x in its retry state
          if (!x()) retry
        }
        true
      } orAtomic { implicit t =>
        false
      }
      if (!f) retry
    }
  }

  test("tryAwait is conservative") {
    val x = Ref(10)
    val t0 = System.currentTimeMillis
    assert(!x.single.tryAwait( _ == 0 , 250))
    val elapsed = System.currentTimeMillis - t0
    assert(elapsed >= 250)
    println("tryAwait(.., 250) took " + elapsed + " millis")
  }

  test("tryAwait in atomic is conservative") {
    val x = Ref(10)
    val t0 = System.currentTimeMillis
    val f = atomic { implicit txn => x.single.tryAwait( _ == 0 , 250) }
    assert(!f)
    val elapsed = System.currentTimeMillis - t0
    assert(elapsed >= 250)
    println("tryAwait(.., 250) inside atomic took " + elapsed + " millis")
  }

  test("retryFor is conservative") {
    val x = Ref(false)
    val t0 = System.currentTimeMillis
    val s = atomic { implicit txn =>
      if (!x()) retryFor(250)
      "timeout"
    }
    assert(s === "timeout")
    val elapsed = System.currentTimeMillis - t0
    assert(elapsed >= 250)
    println("retryFor(250) took " + elapsed + " millis")
  }

  test("retryFor earliest is first") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      if (!x()) retryFor(100)
      "first"
    } orAtomic { implicit txn =>
      if (!x()) retryFor(200)
      "second"
    }
    assert(s === "first")
  }

  test("retryFor earliest is second") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      if (!x()) retryFor(300)
      "first"
    } orAtomic { implicit txn =>
      if (!x()) retryFor(100)
      "second"
    }
    assert(s === "second")
  }

  test("retryFor earliest is first nested") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      atomic { implicit txn =>
        if (!x()) retryFor(100)
        "first"
      } orAtomic { implicit txn =>
        if (!x()) retryFor(200)
        "second"
      }
    }
    assert(s === "first")
  }

  test("retryFor earliest is second nested") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      atomic { implicit txn =>
        if (!x()) retryFor(300)
        "first"
      } orAtomic { implicit txn =>
        if (!x()) retryFor(100)
        "second"
      }
    }
    assert(s === "second")
  }

  test("retryFor only is first") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      if (!x()) retryFor(100)
      "first"
    } orAtomic { implicit txn =>
      if (!x()) retry
      "second"
    }
    assert(s === "first")
  }

  test("retryFor only is second") {
    val x = Ref(false)
    val s = atomic { implicit txn =>
      if (!x()) retry
      "first"
    } orAtomic { implicit txn =>
      if (!x()) retryFor(100)
      "second"
    }
    assert(s === "second")
  }

  test("retryFor ladder") {
    val buf = new StringBuilder
    val x = Ref(0)
    atomic { implicit txn =>
      buf += 'a'
      retryFor(1)
      buf += 'b'
      retryFor(2)
      buf += 'c'
      retryFor(2)
      buf += 'd'
      retryFor(3)
      buf += 'e'
      retryFor(4)
      buf += 'f'
    } orAtomic { implicit txn =>
      if (x() == 0) retry
    }
    assert(buf.result === "aababcdabcdeabcdef")
  }

  test("late start retryFor") {
    val x = Ref(0)
    val begin = System.currentTimeMillis
    (new Thread { override def run { Thread.sleep(100) ; x.single() = 1 } }).start
    val buf = new StringBuilder
    atomic { implicit txn =>
      buf += 'a'
      if (x() == 0) retry
      buf += 'b'
      retryFor(200)
      buf += 'c'
    }
    val elapsed = System.currentTimeMillis - begin
    println("late start retryFor(200) inside atomic took " + elapsed + " millis")
    assert(elapsed >= 200 && elapsed < 300)
    assert(buf.result === "aababc")
  }

  test("expired start retryFor") {
    val x = Ref(0)
    val begin = System.currentTimeMillis
    (new Thread { override def run { Thread.sleep(200) ; x.single() = 1 } }).start
    val buf = new StringBuilder
    atomic { implicit txn =>
      buf += 'a'
      if (x() == 0) retry
      buf += 'b'
      retryFor(100)
      buf += 'c'
    }
    val elapsed = System.currentTimeMillis - begin
    println("expired(200) start retryFor(100) inside atomic took " + elapsed + " millis")
    assert(elapsed >= 200 && elapsed < 300)
    assert(buf.result === "aabc")
  }

  test("retryFor as sleep") {
    val begin = System.currentTimeMillis
    atomic { implicit txn => retryFor(100) }
    val elapsed = System.currentTimeMillis - begin
    println("retryFor(100) as sleep took " + elapsed + " millis")
    assert(elapsed >= 100 && elapsed < 200)
  }

  test("View in txn") {
    val x = Ref(10)
    val xs = x.single
    atomic { implicit t =>
      x += 1
      assert(x() === 11)
      assert(xs() === 11)
      xs += 1
      assert(x() === 12)
      assert(xs() === 12)
      x.single += 1
      assert(x() === 13)
      assert(xs() === 13)
      assert(x.single() === 13)
      x.single() = 14
      assert(x() === 14)
    }
  }

  test("currentLevel during nesting") {
    // this test is _tricky_, because an assertion failure inside the atomic
    // block might cause a restart that expands any subsumption
    val (n0, n1, n2) = atomic { implicit t =>
      val (n1, n2) = atomic { implicit t =>
        val n2 = atomic { implicit t =>
          NestingLevel.current
        }
        (NestingLevel.current, n2)
      }
      (NestingLevel.current, n1, n2)
    }
    assert(n2.parent.get eq n1)
    assert(n2.root eq n0)
    assert(n1.parent.get eq n0)
    assert(n1.root eq n0)
    assert(n0.parent.isEmpty)
  }

  test("persistent rollback") {
    val x = Ref(0)
    var okay = true
    intercept[UserException] {
      atomic { implicit txn =>
        x() = 1
        try {
          Txn.rollback(Txn.UncaughtExceptionCause(new UserException()))
        } catch {
          case _ => // swallow
        }
        x()
        okay = false
      }
    }
    assert(okay)
  }

  test("persistent rollback via exception") {
    val x = Ref(0)
    intercept[UserException] {
      atomic { implicit txn =>
        x() = 1
        try {
          Txn.rollback(Txn.UncaughtExceptionCause(new UserException()))
        } catch {
          case _ => // swallow
        }
        throw new InterruptedException // this should be swallowed
      }
    }
  }

  test("barging retry") {
    // the code to trigger barging is CCSTM-specific, but this test should pass regardless
    var tries = 0
    val x = Ref(0)
    val y = Ref(0)
    val z = Ref(0)

    (new Thread { override def run { Thread.sleep(100) ; x.single() = 1 ; y.single() = 1 } }).start

    atomic { implicit txn =>
      z() = 2
      atomic { implicit txn =>
        NestingLevel.current
        tries += 1
        if (tries < 50)
          Txn.rollback(Txn.OptimisticFailureCause('test, None))

        z() = 3
        if (x() != 1 || y.swap(2) != 1)
          retry
      }
    }
  }

  test("retry with many pessimistic reads") {
    // the code to trigger barging is CCSTM-specific, but this test should pass regardless
    var tries = 0
    val refs = Array.tabulate(10000) { _ => Ref(0) }

    (new Thread { override def run { Thread.sleep(100) ; refs(500).single() = 1 } }).start

    atomic { implicit txn =>
      tries += 1
      if (tries < 50)
        Txn.rollback(Txn.OptimisticFailureCause('test, None))

      val sum = refs.foldLeft(0)( _ + _.get )
      if (sum == 0)
        retry
    }
  }

  test("remote cancel") {
    val x = Ref(0)

    val finished = atomic { implicit txn =>
      x += 1
      NestingLevel.current
    }
    assert(x.single() === 1)

    for (i <- 0 until 100) {
      intercept[UserException] {
        atomic { implicit txn =>
          val active = NestingLevel.current
          (new Thread {
            override def run {
              val cause = Txn.UncaughtExceptionCause(new UserException)
              assert(finished.requestRollback(cause) === Txn.Committed)
              assert(active.requestRollback(cause) == Txn.RolledBack(cause))
            }
          }).start

          while (true)
            x() = x() + 1
        }
      }
      assert(x.single() === 1)
    }
  }

  test("remote cancel of root") {
    val x = Ref(0)

    val finished = atomic { implicit txn =>
      x += 1
      NestingLevel.current
    }
    assert(x.single() === 1)

    for (i <- 0 until 100) {
      intercept[UserException] {
        atomic { implicit txn =>
          // this is to force true nesting for CCSTM, but the test should pass for any STM
          atomic { implicit txn => NestingLevel.current }

          val active = NestingLevel.current
          (new Thread {
            override def run {
              Thread.`yield`
              Thread.`yield`
              val cause = Txn.UncaughtExceptionCause(new UserException)
              assert(finished.requestRollback(cause) === Txn.Committed)
              assert(active.requestRollback(cause) == Txn.RolledBack(cause))
            }
          }).start

          while (true)
            atomic { implicit txn => x += 1 }
        }
      }
      assert(x.single() === 1)
    }
  }

  test("remote cancel of child") {
    val x = Ref(0)

    for (i <- 0 until 100) {
      intercept[UserException] {
        atomic { implicit txn =>
          atomic { implicit txn =>
            val active = NestingLevel.current
            (new Thread {
              override def run {
                Thread.sleep(50)
                val cause = Txn.UncaughtExceptionCause(new UserException)
                assert(active.requestRollback(cause) == Txn.RolledBack(cause))
              }
            }).start

            while (true)
              x() = x() + 1
          }
        }
      }
      assert(x.single() === 0)
    }
  }

  test("toString") {
    (atomic { implicit txn =>
      txn.toString
      txn
    }).toString
    (atomic { implicit txn =>
      NestingLevel.current.toString
      NestingLevel.current
    }).toString
  }

  perfTest("uncontended R+W txn perf") { (x, y) =>
    var i = 0
    while (i < 5) {
      i += 1
      atomic { implicit t =>
        assert(x() == "abc")
        x() = "def"
      }
      atomic { implicit t =>
        assert(x() == "def")
        x() = "abc"
      }
    }
  }

  for (depth <- List(0, 1, 2, 8)) {
    perfTest("uncontended R+W txn perf: nesting depth " + depth) { (x, y) =>
      var i = 0
      while (i < 5) {
        i += 1
        nested(depth) { implicit t =>
          assert(x() == "abc")
          x() = "def"
        }
        nested(depth) { implicit t =>
          assert(x() == "def")
          x() = "abc"
        }
      }
    }
  }

  perfTest("uncontended R+R txn perf") { (x, y) =>
    var i = 0
    while (i < 10) {
      i += 1
      atomic { implicit t =>
        assert(x() == "abc")
        assert(y() == 10)
      }
    }
  }

  for (depth <- List(0, 1, 2, 8)) {
    perfTest("uncontended R+R txn perf: nesting depth " + depth) { (x, y) =>
      var i = 0
      while (i < 10) {
        i += 1
        nested(depth) { implicit t =>
          assert(x() == "abc")
          assert(y() == 10)
        }
      }
    }
  }

//  for (i <- 0 until 50) {
//    perfTest("uncontended R+R txn perf: nesting depth 8, take " + i) { (x, y) =>
//      var i = 0
//      while (i < 10) {
//        i += 1
//        nested(8) { implicit t =>
//          assert(x() == "abc")
//          assert(y() == 10)
//        }
//      }
//    }
//  }

  private def nested(depth: Int)(body: InTxn => Unit) {
    atomic { implicit txn =>
      if (depth == 0)
        body(txn)
      else
        nested(depth - 1)(body)
    }
  }

  private def perfTest(name: String)(runTen: (Ref[String], Ref[Int]) => Unit) {
    test(name, Slow) {
      val x = Ref("abc")
      val y = Ref(10)
      var best = java.lang.Long.MAX_VALUE
      for (pass <- 0 until 50000) {
        val begin = System.nanoTime
        runTen(x, y)
        val elapsed = System.nanoTime - begin
        best = best min elapsed
      }
      println(name + ": best was " + (best / 10.0) + " nanos/txn")
    }
  }
}
