package io.chrisdavenport


import fs2._
import fs2.io.tcp
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import org.http4s._

package object ember {
  private val logger = org.log4s.getLogger

  def server[F[_]: Effect](
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 *1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds,
    bindAddress: InetSocketAddress,
    service: HttpService[F],
    onMissing: Response[F],
    onError: Throwable => Stream[F, Response[F]],
    onWriteFailure : Throwable => Stream[F, Nothing],
    ec: ExecutionContext,
    ag: AsynchronousChannelGroup,
    terminationSignal: async.immutable.Signal[F, Boolean],
    exitCode: async.Ref[F, StreamApp.ExitCode]
  ): Stream[F, StreamApp.ExitCode] = {
    implicit val AG = ag
    implicit val EC = ec
    val (initial, readDuration) = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => (true, fin)
      case _ => (false, 0.millis)
    }

    tcp.server[F](bindAddress)
      .map(_.flatMap(
        socket =>
          Stream.eval(async.signalOf[F, Boolean](initial)).flatMap{ 
            timeoutSignal =>  
            Server.readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
              .through(Server.requestPipe)
              .take(1)
              .flatMap{ req => 
                Stream.eval_(Sync[F].delay(logger.debug(s"Request Processed $req"))) ++
                Stream.eval_(timeoutSignal.set(false)) ++
                Stream(req).covary[F].through(Server.httpServiceToPipe[F](service, onMissing)).take(1)
                  .handleErrorWith(onError).take(1)
                  .flatTap(resp => Stream.eval(Sync[F].delay(logger.debug(s"Response Created $resp"))))
                  .map(resp => (req, resp))
              }
              .attempt
              .evalMap{ attempted => 
                def send(request:Option[Request[F]], resp: Response[F]): F[Unit] = {
                  Stream(resp)
                  .covary[F]
                  .through(Server.respToBytes[F])
                  .through(socket.writes())
                  .onFinalize(socket.endOfOutput)
                  .compile
                  .drain
                  .attempt
                  .flatMap{
                    case Left(err) => onWriteFailure(err).compile.drain
                    case Right(()) => Sync[F].pure(())
                  }
                }
                attempted match {
                  case Right((request, response)) => send(Some(request), response)
                  case Left(err) => onError(err).evalMap { send(None, _) }.compile.drain
                }
              }.drain
          }
        )).join(maxConcurrency)
          .interruptWhen(terminationSignal)
          .drain ++ Stream.eval(exitCode.get)
  }

}