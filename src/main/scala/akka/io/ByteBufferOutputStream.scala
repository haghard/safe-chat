package akka.io

final class ByteBufferOutputStream(byteBuffer: java.nio.ByteBuffer) extends java.io.OutputStream {

  @throws[java.io.IOException]
  override def write(b: Int): Unit = {
    if (!byteBuffer.hasRemaining)
      flush()

    byteBuffer.put(b.toByte)
  }

  @throws[java.io.IOException]
  override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
    if (byteBuffer.remaining < length)
      flush()

    byteBuffer.put(bytes, offset, length)
  }
}
