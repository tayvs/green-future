package v0

import com.typesafe.scalalogging.{Logger, StrictLogging}

object qqqq extends App with StrictLogging {
  implicit val l: Logger = logger

  val f1 = GreenFuture(25)
  logger.info(f1.toString)
  val f11 = f1.map(v => v + 1)
  logger.info(f11.toString)

  f11.map(v => logger.info(s"[f1]${Thread.currentThread()}")).log

  val p1 = GreenFuture.promise[Int]
  val f2 = p1.future
  println(f2)
  p1.complete(42)
  println(f2)

  f2.map(v => logger.info(s"[f2]${Thread.currentThread()}")).log
}