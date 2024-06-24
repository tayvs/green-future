
import com.typesafe.scalalogging.{LazyLogging, Logger, StrictLogging}

import java.lang.Thread.Builder
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


object qqqq extends App with StrictLogging {

  // TODO: reimplement Promise. Fulfill promise on current thread and return Boolean on completing method
  //  AND run it further on new VirtualThread
  val c1 = Chain(42)
  logger.info(c1.toString) // TODO: Incomplete or complete. Virtual Thread creation is instant action and it not block caller thread
  logger.info(c1.map(_ + 42).toString) // see above

  val cp1 = new ChainPromise[Int]
  val c2 = cp1.chain
  logger.info("c2 is " + c2) // Incomplete
  cp1.complete(22)
  Thread.sleep(5)
  logger.info("c2 is " + c2) // Chain(ChainContext(Success(22)))
  c2.map { v =>
    val nVal = v + 1
    logger.info(s"[${Thread.currentThread()}] Chain 1. $nVal") // 23
    nVal
  }.map { v =>
    val nVal = v + 1
    logger.info(s"[${Thread.currentThread()}] Chain 2. $nVal") // 24
    nVal
  }

  c2.map { v =>
    val nVal = v * 2
    logger.info(s"[${Thread.currentThread()}] Chain 3. $nVal") // 44
    nVal
  }

  c2
    .map { v =>
      logger.info(s"[${Thread.currentThread()}] Chain 4. $v") // 22
      throw new RuntimeException("Chain 4")
      42
    }
    .andThen { case r => logger.info(s"[${Thread.currentThread()}] Chain 4 result: '$r'") }

  c2.flatMap { v =>
      Thread.sleep(5)
      logger.info(s"[${Thread.currentThread()}] Chain 5. $v")
      Chain {
        Thread.sleep(5)
        "Chain 5 value"
      }
    }
    .map(v => logger.info(s"[${Thread.currentThread()}] Chain 5 value: '$v'"))

  val promiseC3 = new ChainPromise[Int]
  val c3 = promiseC3.chain
  promiseC3.complete(42)

  c2.flatMap { v =>
      logger.info(s"[${Thread.currentThread()}] Chain 6. $v")
      c3.map(_ + 42)
    }
    .map(v => logger.info(s"[${Thread.currentThread()}] Chain 6 value: '$v'"))


  Thread.sleep(100)

  {
    logger.info("Virtual tread")
    val start = System.nanoTime()
    logger.info(s"Before start.")
    Thread.ofVirtual().start { () =>
      val started = System.nanoTime()
      logger.info(s"In start. ${started - start}")
    }
    val finished = System.nanoTime()
    logger.info(s"Before finished. ${finished - start}")
  }
  Thread.sleep(10)

}

//sealed trait Chain

//abstract class ChainContextfulFunction[I, O](implicit context: ChainContext[_]) {
//
//  protected val aaaaaa = 42
//
//  def apply(i: I): O
//}

// Extend to hold some ThreadLocal-like values and ZIO-like StackTraces
case class ChainContext[T](val t: Try[T])

object ChainContext {
  def apply[T](v: T): ChainContext[T] = new ChainContext(Success(v))

  val Empty: ChainContext[Nothing] = new ChainContext[Nothing](null)

  //  def apply(implicit c: ChainContext[_]): ChainContext[_] = c
  //
  //  def map[I, O](f: ChainContextfulFunction[I, O]) = ???
  //
  //  val fff: ChainContextfulFunction[Int, Int] = new ChainContextfulFunction[Int, Int](???) {
  //	override def apply(i: Int): Int = {
  //  	ChainContext.apply.t
  //  	this.aaaaaa
  //	}
  //  }
  //  map({ i: Int =>
  //	i + 1
  //	this.aaaaaa
  //	ChainContext.apply.t
  //  }: ChainContextfulFunction)
}

/**
 * @param f
 *                is a Try[Any] => Try[T] function for Sync
 *                and is a Try[Any] => Chain[T] function for Async
 * @param typeTag - 0 or 1. Sync or Async respectively
 * @tparam T - Success Value type
 */
class Chain[T](val f: Any => Any, val typeTag: Int) {
  self =>

  sealed trait State

  class Callbacks(val chains: List[Chain[_]]) extends State {
    def addCallback(c: Chain[_]): Callbacks = new Callbacks(c :: chains)
  }

  class Value(val value: ChainContext[T]) extends State

  def value: Option[ChainContext[T]] = state.get() match {
    case v: Value => Some(v.value)
    case _ => None
  }

  private val state: AtomicReference[State] = new AtomicReference[State](new Callbacks(Nil))

  override def toString = s"Chain(${value.getOrElse("<Incomplete>")})"

  @tailrec
  private def addOrExecute[U](newChain: Chain[U]): Unit =
    state.get() match {
      case cs: Callbacks => if (state.compareAndSet(cs, cs.addCallback(newChain))) () else addOrExecute(newChain)
      case value: Value => ChainExecutor.execute(newChain, value.value)
    }

  def transform[U](f: Try[T] => Try[U]): Chain[U] = {
    val newChain = new Chain[U](f.asInstanceOf[Any => Any], Chain.typeTagSync)
    addOrExecute(newChain)

    newChain
  }

  // Better impl for FlatMaps? adapter for chain that is still to come. OR use callbacks to propagate Value to ChainWrapper
  // Current implementation wrap provided function to complete Promise with value of function result AND returning link on Future of promise
  // On Execution, Function just run and not continue the execution. TODO Could be corner cases
  def transformWith[U](f: Try[T] => Chain[U]): Chain[U] = {
    val promise = new ChainPromise[U]
    val wrappedDeferredChainFunc: Try[T] => Chain[U] = { (t: Try[T]) =>
      try f(t).andThen { t => promise.completeTry(t) }
      catch {
        case t: Throwable =>
          promise.fail(t)
          Chain.failed(t)
      }
    }
    val newChain = new Chain[U](wrappedDeferredChainFunc.asInstanceOf[Any => Any], Chain.typeTagAsync)
    addOrExecute(newChain)

    promise.chain
  }

  def onComplete(f: Try[T] => Unit): Unit = {
    val newChain = new Chain(f.asInstanceOf[Any => Any], Chain.typeTagSync)
    addOrExecute(newChain)
  }

  def andThen[U](f: PartialFunction[Try[T], U]): Chain[T] = {
    val newChain = new Chain[U](f.lift.asInstanceOf[Any => Any], Chain.typeTagSync)
    addOrExecute(newChain)

    this
  }

  def map[U](f: T => U): Chain[U] = transform {
    _.map(f)
  }

  def flatMap[U](f: T => Chain[U]): Chain[U] = transformWith {
    case Success(value) => f(value)
    case Failure(e) => Chain.failed[U](e)
  }

  def recover[U >: T](pf: PartialFunction[Throwable, U]): Chain[U] = transform {
    _.recover(pf)
  }

  def recoverWith[U >: T](pf: PartialFunction[Throwable, Chain[U]]): Chain[U] = transformWith {
    case Failure(ex) if pf.isDefinedAt(ex) => pf(ex)
    case _ => this.asInstanceOf[Chain[U]]
  }

  @tailrec
  private def setValueState(v: ChainContext[T]): List[Chain[_]] =
    state.get() match {
      case cs: Callbacks => if (state.compareAndSet(cs, new Value(v))) cs.chains else setValueState(v)
      case _: Value => throw new IllegalStateException("Assign value to completed Future")
    }

  def getChains(value: ChainContext[Any]): (ChainContext[T], List[Chain[_]]) = {
    // Should be moved to executor
    val ret: (ChainContext[T], List[Chain[_]]) = typeTag match {
      case Chain.typeTagSync =>
        val nValue: ChainContext[T] =
          try ChainContext(f.asInstanceOf[Try[Any] => Try[T]](value.t))
          catch {
            case t: Throwable => ChainContext(Failure(t))
          }
        val chains = setValueState(nValue)
        nValue -> chains
      case Chain.typeTagAsync =>
        val nContext = try {
          val nChain: Chain[T] = f.asInstanceOf[Try[Any] => Chain[T]](value.t)
          // Save inner state?
          nChain.value.getOrElse(ChainContext.Empty.asInstanceOf[ChainContext[T]])
        } catch {
          case t: Throwable => ChainContext[T](Failure(t))
        }
        nContext -> Nil
    }
    ret
  }
}

object Chain {
  val typeTagSync = 0
  val typeTagAsync = 1

  val unit = new Chain(_ => (), Chain.typeTagSync)

  def apply[T](t: T): Chain[T] = {
    val promise = new ChainPromise[T]
    promise.complete(t)
    promise.chain
  }

  def failed[T](t: Throwable): Chain[T] = {
    val promise = new ChainPromise[T]
    promise.fail(t)
    promise.chain
  }

  def promise[T]: ChainPromise[T] = new ChainPromise[T]

}

class ChainPromise[T] {
  private val completed: AtomicBoolean = new AtomicBoolean(false)
  val chain: Chain[T] = new Chain[T](identity, Chain.typeTagSync)

  def complete(value: T): Boolean = completeTry(Success(value))

  def fail(value: Throwable): Boolean = completeTry(Failure(value))

  // TODO: improve Boolean value. Complete on caller thread???
  def completeTry(t: Try[T]): Boolean =
    if (completed.compareAndSet(false, true)) {
      ChainExecutor.execute(chain, new ChainContext[T](t))
      true
    } else false
}

object ChainExecutor {

  private val virtualThreadBuilder: Builder.OfVirtual =
    Thread.ofVirtual().inheritInheritableThreadLocals(true).name("ChainExecutor")

  def execute[T](c: Chain[T], value: ChainContext[_]): Unit = virtualThreadBuilder.start { () =>
    var oldValue: ChainContext[Any] = value.asInstanceOf[ChainContext[Any]]
    var curChain: Chain[Any] = c.asInstanceOf[Chain[Any]]
    //
    //	@tailrec
    //	def innerExecute(c: Chain[Any], value: Any): Unit = {
    //  	val (curVal, chains) = c.getChains(value)
    //  	println("[innerExecute] curVal " + curVal)
    //  	println("[innerExecute] chains " + chains)
    //  	val head = chains.headOption.orNull
    //  	val tail = if (chains.size > 1) chains.tail else Nil
    //  	tail.foreach { execute(_, curVal) }
    //
    //  	oldValue = curVal
    //  	curChain = head.asInstanceOf[Chain[Any]]
    //  	if (curChain != null) innerExecute(curChain, oldValue) else ()
    //	}
    //	innerExecute(curChain, oldValue)
    while (curChain != null) {
      // GetChains could be called twice? check if value already defined and return empty values?
      val (curVal, chains) = curChain.getChains(oldValue)
      //  	println("[execute] curVal " + curVal)
      //  	println("[execute] chains " + chains)
      val head = chains.headOption.orNull
      if (chains.size > 1) chains.tail.foreach {
        execute(_, curVal)
      }

      oldValue = curVal
      curChain = head.asInstanceOf[Chain[Any]]
    }

    //	println("[Execute] done")
    Thread.currentThread().join()
  }

}
