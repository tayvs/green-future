package v0

import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.AtomicReference

class Promise[T] {
  val future: GreenFuture[T] = new GreenFuture[T]()

  def complete(value: T): Unit = future.propagateValue(value)
}

class GreenFuture[T] /*private*/ (
                                   private var initValue: Option[T] = None
                                 ) {

  sealed trait State

  class Callbacks(val callbacks: List[T => Unit]) extends State {
    def addCallback(c: T => Unit): Callbacks = new Callbacks(c :: callbacks)
  }

  class Value(val value: T) extends State

  private val state: AtomicReference[State] = {
    val initState = initValue match {
      case Some(v) => new Value(v)
      case None => new Callbacks(List())
    }
    new AtomicReference[State](initState)
  }

  def value: Option[T] = state.get() match {
    case v: Value => Some(v.value)
    case _ => None
  }

  override def toString: String = value.fold("GreenFuture(Incomplete)")(v => s"GreenFuture($v)")

  /*private*/
  // TODO: Should return next task provider for Virtual Thread that will provide a value
  def propagateValue(value: T /*, thread: Option[Thread] = None*/): Unit = {
    val oldState = state.get()
    oldState match {
      case c: Callbacks =>
        if (state.compareAndSet(oldState, new Value(value))) c.callbacks.foreach(callback => callback(value))
        else propagateValue(value /*, thread*/)
      case _: Value => throw new IllegalStateException("Value already set")
    }
  }

  private def onValue(f: T => Unit): Unit = {
    val oldState = state.get()
    oldState match {
      case c: Callbacks => if (state.compareAndSet(oldState, c.addCallback(f))) () else onValue(f)
      case v: Value => f(v.value)
    }
  }

  def map[U](f: T => U): GreenFuture[U] = {
    val newGT = new GreenFuture[U]()
    onValue(v => newGT.propagateValue(f(v)))
    newGT
  }

  def log(implicit logger: Logger): Unit = map { v =>
    logger.info(s"[${Thread.currentThread()}] Value: $v")
    v
  }

}

object GreenFuture {

  def apply[T](t: T): GreenFuture[T] = {
    val gf = new GreenFuture[T]()
    Thread.startVirtualThread(() => gf.propagateValue(t))
    gf
  }

  def promise[T]: Promise[T] = new Promise[T]

}
