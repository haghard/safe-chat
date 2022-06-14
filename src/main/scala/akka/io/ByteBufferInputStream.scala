package akka.io

/** Simple {@link InputStream} implementation that exposes currently available content of a {@link ByteBuffer}.
  *
  * Simular to com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
  */
final class ByteBufferInputStream(buf: java.nio.ByteBuffer) extends java.io.InputStream {

  override def available: Int = buf.remaining

  override def read(): Int =
    if (buf.hasRemaining()) buf.get() else -1

  @throws[java.io.IOException]
  override def read(bytes: Array[Byte], off: Int, len: Int): Int =
    if (buf.hasRemaining()) {
      val l = Math.min(len, buf.remaining())
      buf.get(bytes, off, l)
      l
    } else -1
}
