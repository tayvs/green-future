
import com.typesafe.scalalogging.{LazyLogging, Logger, StrictLogging}

import java.lang.Thread.Builder
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


object qqqq extends App with StrictLogging {

  val c1 = Chain(42)
  logger.info(c1.toString)
  logger.info(c1.map(_ + 42).toString)

  val cp1 = new ChainPromise[Int]
  val c2 = cp1.chain
  logger.info("c2 is " + c2)
  cp1.complete(22)
  Thread.sleep(5)
  logger.info("c2 is " + c2)
  c2.map { v =>
    logger.info(s"[${Thread.currentThread()}] Chain 1")
    v + 1
  }.map { v =>
    logger.info(s"[${Thread.currentThread()}] Chain 2")
    v + 1
  }

  c2.map { v =>
    logger.info(s"[${Thread.currentThread()}] Chain 3")
    v * 2
  }

  c2.map { v =>
    logger.info(s"[${Thread.currentThread()}] Chain 4")
    throw new RuntimeException("Chain 4")
  }

  c2.flatMap { v =>
      Thread.sleep(5)
      logger.info(s"[${Thread.currentThread()}] Chain 5")
      Chain {
        Thread.sleep(5)
        "Chain 5 value"
      }
    }
    .map(v => logger.info(s"[${Thread.currentThread()}] Chain 5 value: '$v''"))

  Thread.sleep(100)

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
  private var nextChains: List[Chain[_]] = Nil // Should have 1 element of next callback chain if typeTag is Async
  private var value: Option[ChainContext[T]] = None

  sealed trait State

  class Callbacks(val chains: List[Chain[_]]) extends State {
    def addCallback(c: Chain[_]): Callbacks = new Callbacks(c :: chains)
  }

  class Value(val value: ChainContext[T]) extends State

  private val state: AtomicReference[State] = new AtomicReference[State](new Callbacks(Nil))

  override def toString = s"Chain(${value.orNull})"

  @tailrec
  private def addOrExecute[U](newChain: Chain[U]): Unit = {
    //    value match {
    //      case Some(value) => ChainExecutor.execute(newChain, value) // execute
    //      case None => nextChains = newChain :: nextChains
    //    }
    val s = state.get()
    s match {
      case cs: Callbacks => if (state.compareAndSet(s, cs.addCallback(newChain))) () else addOrExecute(newChain)
      case value: Value => ChainExecutor.execute(newChain, value.value)
    }
  }

  def transform[U](f: Try[T] => Try[U]): Chain[U] = {
    val newChain = new Chain[U](f.asInstanceOf[Any => Any], Chain.typeTagSync)
    addOrExecute(newChain)

    newChain
  }

  // Better impl for FlatMaps? adapter for chain that is still to come. OR use callbacks to propagate Value to ChainWrapper
  def transformWith[U](f: Try[T] => Chain[U]): Chain[U] = {
    val promise = new ChainPromise[U]
    val wrappedDeferredChainFunc: Try[T] => Chain[U] =
      (t: Try[T]) =>
        f(t).transform { t =>
          promise.completeTry(t)
          t
        }
    val newChain = new Chain[U](wrappedDeferredChainFunc.asInstanceOf[Any => Any], Chain.typeTagAsync)
    addOrExecute(newChain)

    promise.chain
  }

  def map[U](f: T => U): Chain[U] = transform {
    _.map(f)
  }
  //  {
  //	val newChain = new Chain[U](f.asInstanceOf[Any => Any], Chain.typeTagSync)
  //	value match {
  //  	case Some(value) => ChainExecutor.execute(newChain, value) // execute
  //  	case None    	=> nextChains = newChain :: nextChains
  //	}
  //
  //	newChain
  //  }

  def flatMap[U](f: T => Chain[U]): Chain[U] = transformWith {
    case Success(value) => f(value)
    case Failure(e) => Chain.failed[U](e)
  }
  //  {
  //	val promise = new ChainPromise[U]
  //	val wrappedDeferredChainFunc: T => Chain[Unit] = (t: T) => f(t).map(promise.complete)
  //	val newChain = new Chain[U](wrappedDeferredChainFunc.asInstanceOf[Any => Any], Chain.typeTagAsync)
  //	addOrExecute(newChain)
  //
  //	promise.chain
  //  }

  def recover[U >: T](pf: PartialFunction[Throwable, U]): Chain[U] = transform {
    _ recover pf
  }

  @tailrec
  private def setValueState(v: ChainContext[T]): List[Chain[_]] = {
    val s = state.get()
    s match {
      case cs: Callbacks => if (state.compareAndSet(s, new Value(v))) cs.chains else setValueState(v)
      case _: Value => throw new IllegalStateException("Assign value to completed Future")
    }
  }

  def getChains(value: ChainContext[Any]): (ChainContext[T], List[Chain[_]]) = {
    // Should be moved to executor
    val ret@(nValue: ChainContext[T], chains) = typeTag match {
      case Chain.typeTagSync =>
        val nValue: ChainContext[T] =
          try ChainContext(f.asInstanceOf[Try[Any] => Try[T]](value.t))
          catch {
            case t: Throwable => ChainContext(Failure(t))
          }

        //    	val nValue: ChainContext[T] = new ChainContext(value.t.flatMap(v => f.asInstanceOf[Try[Any] => Try[T]](v)))
        //
        //        self.value = Some(nValue)
        // TODO
        val chains = setValueState(nValue)
        (nValue -> chains)
      case Chain.typeTagAsync =>
        try {
          val nChain: Chain[T] = f.asInstanceOf[Try[Any] => Chain[T]](value.t)
          // Save inner state?
          nChain.value.getOrElse {
            //      	ChainExecutor.execute(nChain, value)
            ChainContext.Empty.asInstanceOf[ChainContext[T]]
          } -> Nil
        } catch {
          case t: Throwable => ChainContext[T](Failure(t)) -> Nil
        }
      //    	value.t match {
      //      	case Failure(_) => value.asInstanceOf[ChainContext[T]]
      //      	case Success(value) =>
      //        	try {
      //          	val nChain: Chain[T] = f.asInstanceOf[Try[Any] => Chain[T]](value)
      //          	nChain.value.getOrElse {
      //            	//      	ChainExecutor.execute(nChain, value)
      //            	ChainContext.Empty.asInstanceOf[ChainContext[T]]
      //          	}
      //        	} catch {
      //          	case t: Throwable => ChainContext(Failure(t))
      //        	}
      //    	}

    }
    //	val nValue = new ChainContext(value.t.map(v => f.asInstanceOf[Any => T](v)))
    //	self.value = Some(nValue)
    //	value.t match {
    //  	case Failure(e) => self.value = Some(value.asInstanceOf[ChainContext[T]])
    //  	case Success(value)      	=>
    //    	try {
    //      	val nValue = new ChainContext(f(value.))
    //      	self.value = Some(nValue)
    //    	} catch {
    //      	case e: Throwable => throw e
    //    	}
    //	}
    //
    //	self.value = Some(nValue)
    //	println(s"getChains(${value.t}) " + nValue.t)
    //    nValue -> nextChains
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
  val chain: Chain[T] = new Chain[T](r => r.asInstanceOf[T], Chain.typeTagSync)

  // TODO: Should return boolean if value was set
  def complete(value: T): Unit = ChainExecutor.execute(chain, ChainContext(value))

  def fail(value: Throwable): Unit = ChainExecutor.execute(chain, new ChainContext[T](Failure(value)))

  def completeTry(t: Try[T]): Unit = ChainExecutor.execute(chain, new ChainContext[T](t))
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

  // vt1 -- C1
  // C1 -- vt1 --> .map -- vt1 --> .map -- vt1 --> .map
  // C1 -- vt2 --> .andThen
}
