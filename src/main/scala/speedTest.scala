import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

// Not a real measurements but could be used as Jmh scenarios

// First Iteration
//Future took 778143 microseconds. Result is '1000001'
//Future promise took 587583 microseconds. Result is '1000001'
//Chain took 272402 microseconds. Result is 'Success(1000001)'
//Chain promise took 92484 microseconds. Result is 'Success(1000001)'

// Second iteration (with chain only to reduce warmup effect)
//Chain took 481760 microseconds. Result is 'Success(1000001)'
//Chain promise took 226912 microseconds. Result is 'Success(1000001)'
object speedTest extends App {

  import scala.concurrent.ExecutionContext.Implicits.global

  @tailrec
  def incrementFutureNTimes(f: Future[Int], n: Int): Future[Int] =
    if (n == 0) f else incrementFutureNTimes(f.map(_ + 1), n - 1)

  @tailrec
  def incrementChainNTimes(f: Chain[Int], n: Int): Chain[Int] =
    if (n == 0) f else incrementChainNTimes(f.map(_ + 1), n - 1)

  @tailrec
  def incrementNTimes(init: Int, n: Int): Int = if (n == 0) init else incrementNTimes(init, n - 1)

  def incrementNTimes2(init: Int, n: Int): Int = {
    val f: Int => Int = (i: Int) => i + 1
    val funcs = LazyList.continually(f)
    val combinedFunc = Function.chain(funcs.take(n))
    combinedFunc(init)
    //	val initFunc: Unit => Int = _ => init
    //	@tailrec
    //	def incrementNTimesInner(f: Unit => Int, n: Int): Unit => Int =
    //  	if (n == 0) f else incrementNTimesInner(f.andThen(_ + 1), n - 1)
    //	incrementNTimesInner(initFunc, n)(())
  }

  //    {
  //  	val initF = Future.successful(1)
  //  	val start = System.nanoTime()
  //  	initF.map(incrementNTimes2(_, 1_000_000)).map { t =>
  //    	val end = System.nanoTime()
  //    	println(s"Increment in future took ${(end - start) / 1_000} microseconds. Result is '$t'")
  //  	}
  //    }

  //    Thread.sleep(1_000)
  //
  //  {
  //    val initF = Future.successful(1)
  //    val start = System.nanoTime()
  //    incrementFutureNTimes(initF, 1_000_000).map { t =>
  //      val end = System.nanoTime()
  //      println(s"Future took ${(end - start) / 1_000} microseconds. Result is '$t'")
  //    }
  //  }
  //
  //  Thread.sleep(1_000)
  //
  //  {
  //    val promise = Promise[Int]()
  //    val initF = promise.future
  //    val start = System.nanoTime()
  //    incrementFutureNTimes(initF, 1_000_000).map { t =>
  //      val end = System.nanoTime()
  //      println(s"Future promise took ${(end - start) / 1_000} microseconds. Result is '$t'")
  //    }
  //    promise.success(1)
  //  }
  //
  //  Thread.sleep(1_000)

  /////////////////////////////////////////////
  /////   CHAIN
  ///////////////////////////////////////////

  //  {
  //    val initF = Chain(1)
  //    val start = System.nanoTime()
  //    initF.map(incrementNTimes2(_, 1_000_000)).transform { t =>
  //      val end = System.nanoTime()
  //      println(s"Increment in chain took ${(end - start) / 1_000} microseconds. Result is '$t'")
  //      t
  //    }
  //  }

  Thread.sleep(1_000)
  //  System.gc()

  {
    val initF = Chain(1)
    val start = System.nanoTime()
    incrementChainNTimes(initF, 1_000_000).transform { t =>
      val end = System.nanoTime()
      println(s"Chain took ${(end - start) / 1_000} microseconds. Result is '$t'")
      t
    }
  }

  Thread.sleep(1_000)
  //  System.gc()

  {
    val promise = Chain.promise[Int]
    val initF = promise.chain
    val start = System.nanoTime()
    incrementChainNTimes(initF, 1_000_000).transform { t =>
      val end = System.nanoTime()
      println(s"Chain promise took ${(end - start) / 1_000} microseconds. Result is '$t'")
      t
    }
    promise.complete(1)
  }

  Thread.sleep(10_000)
  System.gc()
}