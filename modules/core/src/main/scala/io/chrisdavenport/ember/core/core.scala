package io.chrisdavenport.ember

import fs2._
import fs2.concurrent._
import fs2.io.tcp
import fs2.io.tcp._
import cats._
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import org.http4s._
import _root_.io.chrisdavenport.ember.core.{Encoder, Parser}
import _root_.io.chrisdavenport.ember.core.Util.readWithTimeout

package object core {

  private val logger = org.log4s.getLogger

  def server[F[_]: ConcurrentEffect](
    bindAddress: InetSocketAddress,
    httpApp: HttpApp[F],
    ag: AsynchronousChannelGroup,
    // Defaults
    onError: Throwable => Response[F] = {_: Throwable => Response[F](Status.InternalServerError)},
    onWriteFailure : Option[(Option[Request[F]], Response[F], Throwable) => F[Unit]] = None,
    terminationSignal: Option[SignallingRef[F, Boolean]] = None,
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 * 1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds
  )(implicit C: Clock[F]): Stream[F, Nothing] = {
    implicit val AG = ag

    // Termination Signal, if not present then does not terminate.
    val termSignal: F[SignallingRef[F, Boolean]] = 
      terminationSignal.fold(SignallingRef[F, Boolean](false))(_.pure[F])

    val writeFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit] = {
      def doNothing(o: Option[Request[F]], r: Response[F], t: Throwable) : F[Unit] = 
        Sync[F].unit
      onWriteFailure match {
        case Some(f ) => f
        case None => doNothing
      }
    }
    
    def socketReadRequest(
      socket: Socket[F], 
      requestHeaderReceiveTimeout: Duration, 
      receiveBufferSize: Int): F[Request[F]] = {
        val (initial, readDuration) = requestHeaderReceiveTimeout match {
          case fin: FiniteDuration => (true, fin)
          case _ => (false, 0.millis)
        }
        SignallingRef[F, Boolean](initial).flatMap{timeoutSignal =>
        C.realTime(MILLISECONDS).flatMap(now => 
          Parser.Request.parser(maxHeaderSize)(
            readWithTimeout[F](socket, now, readDuration, timeoutSignal.get, receiveBufferSize)
          )
            .flatMap{req => 
              Sync[F].delay(logger.debug(s"Request Processed $req")) *>
              timeoutSignal.set(false).as(req)
            }
        )}
      }

    Stream.eval(termSignal).flatMap(terminationSignal => 
    tcp.server[F](bindAddress)
      .map(connect => 
        Stream.eval(
          connect.use{socket =>
            val app: F[(Request[F], Response[F])] = for {
              req <- socketReadRequest(socket, requestHeaderReceiveTimeout, receiveBufferSize)
              resp <- httpApp.run(req)
                .handleError(onError)
                .flatTap(resp => Sync[F].delay(logger.debug(s"Response Created $resp")))
            } yield (req, resp)
            def send(request:Option[Request[F]], resp: Response[F]): F[Unit] = {
              Stream(resp)
                .covary[F]
                .flatMap(Encoder.respToBytes[F])
                .through(socket.writes())
                .compile
                .drain
                .attempt
                .flatMap{
                  case Left(err) => writeFailure(request, resp, err)
                  case Right(()) => Sync[F].pure(())
                }
            }
            app.attempt.flatMap{
              case Right((request, response)) => send(Some(request), response)
              case Left(err) => send(None, onError(err))
            }
          }
        )
      ).parJoin(maxConcurrency)
        .interruptWhen(terminationSignal)
        .drain
    )
  }


  def request[F[_]: ConcurrentEffect: ContextShift](
    request: Request[F]
    , sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , sslContext : SSLContext = SSLContext.getDefault
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  )(implicit T: Timer[F]): Resource[F, Response[F]] = {
    implicit val ACG : AsynchronousChannelGroup = acg

    def onNoTimeout(socket: Socket[F]): F[Response[F]] = 
      Parser.Response.parser(maxResponseHeaderSize)(
        Encoder.reqToBytes(request)
        .to(socket.writes(None))
        .last
        .onFinalize(socket.endOfOutput)
        .flatMap { _ => socket.reads(chunkSize, None)}
      )

    def onTimeout(socket: Socket[F], fin: FiniteDuration): F[Response[F]] = for {
      start <- T.clock.realTime(MILLISECONDS)
      _ <- Sync[F].delay(println(s"Attempting to write Request $request"))
      _ <- (
        Encoder.reqToBytes(request)
        .to(socket.writes(Some(fin)))
        .compile
        .drain >>
        Sync[F].delay(println("Finished Writing Request"))
      ).start
      timeoutSignal <- SignallingRef[F, Boolean](true)
      sent <- T.clock.realTime(MILLISECONDS)
      remains = fin - (sent - start).millis
      resp <- Parser.Response.parser[F](maxResponseHeaderSize)(
          readWithTimeout(socket, start, remains, timeoutSignal.get, chunkSize)
      )
      _ <- timeoutSignal.set(false).void
    } yield resp

    for {
      address <- Resource.liftF(Shared.addressForRequest(request))
      initSocket <- io.tcp.client[F](address)
      socket <- Resource.liftF{
        if (request.uri.scheme.exists(_ === Uri.Scheme.https)) 
          Sync[F].delay(println("Elevating Socket to ssl")) *>
          Util.liftToSecure[F](
            sslExecutionContext, sslContext
          )(
            initSocket, true
          )(
            request.uri.authority.getOrElse(Uri.Authority()).host.value,
            request.uri.authority.getOrElse(Uri.Authority()).port.getOrElse(443)
          )
        else Applicative[F].pure(initSocket)
      }
      _ <- Resource.liftF(Sync[F].delay(println("Received Final Socket")))
      resp <- timeout match {
        case t: FiniteDuration => Resource.liftF(onTimeout(socket, t))
        case _ => Resource.liftF(onNoTimeout(socket))
      }
    } yield resp

  }

}