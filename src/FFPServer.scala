package com.paulasmuth.sqltap

import java.io._
import java.net._
import java.nio._
import java.nio.channels._
import scala.collection.mutable.ListBuffer
import java.math.BigInteger
import java.util.concurrent._
import java.util.Arrays

class FFPServer(port: Int, num_threads: Int){

  val BUFFER_SIZE = 4096 * 8

  // request header length in bytes, magic bytes
  val REQUEST_SIZE   = 20
  val REQUEST_MAGIC  = Array[Byte](0x17, 0x01)

  // response header length in bytes, magic bytes
  val RESPONSE_SIZE  = 16
  val RESPONSE_MAGIC = Array[Byte](0x17, 0x02)

  val selector = Selector.open
  val server_sock = ServerSocketChannel.open
  val connections = ListBuffer[FFPConnection]()

  val tmp_buffer = ByteBuffer.allocate(BUFFER_SIZE)
  val thread_pool = Executors.newFixedThreadPool(num_threads)

  class FFPConnection(sock: SocketChannel) {
    SQLTap.log_debug("[FFP] connection opened")

    val rbuf = new Array[Byte](BUFFER_SIZE * 2)
    var rbuf_len = 0
    val wbuf = new Array[Byte](BUFFER_SIZE * 2)
    var wbuf_len = 0

    sock.configureBlocking(false)
    val key = sock.register(selector, SelectionKey.OP_READ)
    key.attach(this)

    def yield_read : Unit = {
      val len = sock.read(tmp_buffer)

      if (len == -1)
        return connection_closed

      System.arraycopy(tmp_buffer.array, 0, rbuf, rbuf_len, len)
      rbuf_len += len

      while (rbuf_len >= REQUEST_SIZE) {
        val req_magic  = new Array[Byte](2)
        val req_id     = new Array[Byte](8)
        val req_res_id = new Array[Byte](2)
        val req_rec_id = new Array[Byte](6)

        System.arraycopy(rbuf, 0,  req_magic,  0, 2)
        System.arraycopy(rbuf, 2,  req_id,     0, 8)
        System.arraycopy(rbuf, 12, req_res_id, 0, 2)
        System.arraycopy(rbuf, 14, req_rec_id, 0, 6)

        System.arraycopy(rbuf, REQUEST_SIZE, rbuf, 0, rbuf_len-REQUEST_SIZE)
        rbuf_len -= REQUEST_SIZE

        if (Arrays.equals(REQUEST_MAGIC, req_magic) unary_!) {
          SQLTap.log("[FFP] read invalid magic bytes")
        } else {
          val res_id = new BigInteger(req_res_id)
          val rec_id = new BigInteger(req_rec_id)
          val pquery = SQLTap.prepared_queries_ffp.getOrElse(res_id.intValue, null)

          if (pquery == null)
            SQLTap.log("[FFP] query for invalid resource_id: " + res_id.toString)

          else
            execute_query(req_id, pquery.build(rec_id.intValue))

        }
      }
    }

    private def connection_closed :  Unit = {
      SQLTap.log_debug("[FFP] connection closed")
      sock.close
      key.cancel
    }

    private def execute_query(req_id: Array[Byte], query: String) : Unit = {
      SQLTap.log_debug("[FFP] Execute: " + query)

      thread_pool.execute(new Runnable {
        def run = try {
          val request = new Request(query,
            new PlainRequestParser, new RequestExecutor, new PrettyJSONWriter)

          request.run

          finish_query(req_id, request)
        } catch {
          case e: Exception => SQLTap.exception(e, true)
        }
      })
    }

    private def finish_query(req_id: Array[Byte], req: Request) : Unit = {
      val resp = req.resp_data.getBytes("UTF-8")
      val flags : Short = 0

      val head = ByteBuffer.allocate(RESPONSE_SIZE)
      head.order(ByteOrder.BIG_ENDIAN)
      head.put(RESPONSE_MAGIC)
      head.put(req_id)
      head.putShort(flags)
      head.putInt(resp.size)

      if ((resp.size + wbuf_len + RESPONSE_SIZE) > (BUFFER_SIZE * 2))
        return SQLTap.log("[FFP] write buffer overflow, discarding request")

      wbuf.synchronized {
        System.arraycopy(head.array, 0, wbuf, wbuf_len, RESPONSE_SIZE)
        System.arraycopy(resp, 0, wbuf, wbuf_len + RESPONSE_SIZE, resp.size)
        wbuf_len += resp.size + RESPONSE_SIZE
      }

      println(hexdump(wbuf, RESPONSE_SIZE))
    }

  }

  def start : Unit = {
    server_sock.socket.bind(new InetSocketAddress("0.0.0.0", port))
    server_sock.configureBlocking(false)
    server_sock.register(selector, SelectionKey.OP_ACCEPT)

    new Thread(new Runnable {
      def run = try { while (true) next }
        catch { case e: Exception => SQLTap.exception(e, true) }
    }).start

    SQLTap.log("Listening on ffp://0.0.0.0:" + port)
  }

  private def next : Unit = {
    selector.select
    val keys = selector.selectedKeys.iterator

    while (keys.hasNext) {
      val key = keys.next
      keys.remove

      if (key.isAcceptable)
        connections += new FFPConnection(server_sock.accept)

      if (key.isReadable)
        key.attachment.asInstanceOf[FFPConnection].yield_read

    }
  }

  private def hexdump(a: Array[Byte], max: Int = 0) : String = {
    var str = javax.xml.bind.DatatypeConverter.printHexBinary(a)
    if (max > 0) str = str.substring(0, max*2)
    str.replaceAll("(.{2})", "$1 ")
  }

}
