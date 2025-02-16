package zhttp.service.server

import io.netty.handler.codec.http.websocketx.{WebSocketServerHandshakerFactory => JWebSocketServerHandshakerFactory}
import zhttp.core._
import zhttp.http.{Response, _}
import zhttp.service._
import zio.Exit

/**
 * Helper class with channel methods
 */
@JSharable
final case class ServerRequestHandler[R](
  zExec: UnsafeChannelExecutor[R],
  app: HttpApp[R, Nothing],
) extends JSimpleChannelInboundHandler[JFullHttpRequest](AUTO_RELEASE_REQUEST)
    with HttpMessageCodec
    with ServerHttpExceptionHandler {

  self =>

  /**
   * Tries to release the request byte buffer, ignores if it can not.
   */
  private def releaseOrIgnore(jReq: JFullHttpRequest): Boolean = jReq.release(jReq.content().refCnt())

  private def webSocketUpgrade(
    ctx: JChannelHandlerContext,
    jReq: JFullHttpRequest,
    res: Response.SocketResponse,
  ): Unit = {
    val hh = new JWebSocketServerHandshakerFactory(jReq.uri(), res.subProtocol.orNull, false).newHandshaker(jReq)
    if (hh == null) {
      JWebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel, ctx.channel().voidPromise())
    } else {
      val pl = ctx.channel().pipeline()
      pl.addLast(WEB_SOCKET_HANDLER, ServerSocketHandler(zExec, res.socket))
      try {
        // handshake can throw
        hh.handshake(ctx.channel(), jReq).addListener { (future: JChannelFuture) =>
          if (!future.isSuccess) {
            pl.remove(WEB_SOCKET_HANDLER)
            ctx.fireExceptionCaught(future.cause)
            ()
          } else {
            pl.remove(HTTP_REQUEST_HANDLER)
            ()
          }
        }
      } catch {
        case _: JWebSocketHandshakeException =>
          JWebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel, ctx.channel().voidPromise())
      }
    }
    ()
  }

  /**
   * Asynchronously executes the Http app and passes the response to the callback.
   */
  private def executeAsync(ctx: JChannelHandlerContext, jReq: JFullHttpRequest)(cb: Response => Unit): Unit =
    app.eval(decodeJRequest(jReq)) match {
      case HttpResult.Success(a)  => cb(a)
      case HttpResult.Failure(_)  => ()
      case HttpResult.Continue(z) =>
        zExec.unsafeExecute(ctx, z) {
          case Exit.Success(res) => cb(res)
          case Exit.Failure(_)   => ()
        }
    }

  /**
   * Unsafe channel reader for HttpRequest
   */
  override def channelRead0(ctx: JChannelHandlerContext, jReq: JFullHttpRequest): Unit = {
    executeAsync(ctx, jReq) {
      case res @ Response.HttpResponse(_, _, _) =>
        ctx.writeAndFlush(encodeResponse(jReq.protocolVersion(), res), ctx.channel().voidPromise())
        releaseOrIgnore(jReq)
        ()
      case res @ Response.SocketResponse(_, _)  =>
        self.webSocketUpgrade(ctx, jReq, res)
        releaseOrIgnore(jReq)
        ()
    }
  }

  /**
   * Handles exceptions that throws
   */
  override def exceptionCaught(ctx: JChannelHandlerContext, cause: Throwable): Unit = {
    if (self.canThrowException(cause)) {
      super.exceptionCaught(ctx, cause)
    }
  }
}
