package io.chrisdavenport


import fs2._
import fs2.concurrent._
import fs2.io.tcp
import fs2.io.tcp._
import cats._
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import org.http4s._
import _root_.io.chrisdavenport.ember.codec.{Encoder, Parser}
import _root_.io.chrisdavenport.ember.util.readWithTimeout

package object ember {

  private val logger = org.log4s.getLogger

  def server[F[_]: ConcurrentEffect: Clock](
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
  ): Stream[F, Nothing] = {
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
          readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
              .through(Parser.Request.parser(maxHeaderSize))
              .take(1)
              .compile
              .lastOrError
              .flatMap{req => 
                Sync[F].delay(logger.debug(s"Request Processed $req")) *>
                timeoutSignal.set(false).as(req)
              }
        }
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
                .onFinalize(socket.endOfOutput)
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
    , acg: AsynchronousChannelGroup
    , sslExecutionContext: ExecutionContext
    , sslContext : SSLContext = { val ctx = SSLContext.getInstance("TLS"); ctx.init(null,null,null); ctx } 
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  )(implicit T: Timer[F]): Resource[F, Response[F]] = {
    implicit val ACG : AsynchronousChannelGroup = acg

    def onNoTimeout(socket: Socket[F]): F[Response[F]] = {
      Encoder.reqToBytes(request)
        .observe(_ => Stream.eval(Sync[F].delay(println("Chunk Written"))))
        .to(socket.writes(None))
        .last
        .onFinalize(socket.endOfOutput)
        .flatMap { _ => socket.reads(chunkSize, None)}
        .through(Parser.Response.parser[F](maxResponseHeaderSize))
        .take(1)
        .compile
        .lastOrError
    }

    def onTimeout(socket: Socket[F], fin: FiniteDuration): F[Response[F]] = for {
      start <- T.clock.realTime(MILLISECONDS)
      e <- Concurrent[F].race(
        T.sleep(fin),
        Encoder.reqToBytes(request)
        .observe(_ => Stream.eval(Sync[F].delay(println("Chunk Written"))))
        .to(socket.writes(Some(fin)))
        .last
        .onFinalize(socket.endOfOutput)
        .compile
        .drain
      ).flatMap{
        _.leftMap(_ => new Throwable("Timeout!")).liftTo[F]
      }

      _ <- Sync[F].delay(println("Finished Writing Request"))
      timeoutSignal <- SignallingRef[F, Boolean](true)
      sent <- T.clock.realTime(MILLISECONDS)
      remains = fin - (sent - start).millis
      resp <- Concurrent[F].race(
        T.sleep(remains),
        readWithTimeout(socket, remains, timeoutSignal.get, chunkSize)
        .observe(_.through(fs2.text.utf8Decode[F]).evalMap(t => Sync[F].delay(println(s"Read - $t"))))
        .through (Parser.Response.parser[F](maxResponseHeaderSize))
        .take(1)
        .compile
        .lastOrError
      ).flatMap{
        _.leftMap(_ => new Throwable("Timeout!")).liftTo[F]
      }
      _ <- timeoutSignal.set(false).void
    } yield resp

    for {
      initSocket <- Resource.liftF(codec.Shared.addressForRequest(request))
        .flatMap(io.tcp.client[F](_))
      _ <- Resource.liftF(Sync[F].delay(println("Initial Socket Received")))
      socket <- Resource.liftF{
        if (request.uri.scheme.exists(_ === Uri.Scheme.https)) util.liftToSecure[F](sslExecutionContext, sslContext)(initSocket, true)
        else Applicative[F].pure(initSocket)
      }
      _ <- Resource.liftF(Sync[F].delay(println("Final Socket Received")))
      resp <- timeout match {
        case t: FiniteDuration => Resource.liftF(onTimeout(socket, t))
        case _ => Resource.liftF(onNoTimeout(socket))
      }
    } yield resp

  }

}