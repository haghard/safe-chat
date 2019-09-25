package com.safechat.domain

import scala.reflect.ClassTag

object RingBuffer {
  def nextPowerOfTwo(value: Int): Int =
    1 << (32 - Integer.numberOfLeadingZeros(value - 1))
}

class RingBuffer[T: ClassTag] private (capacity: Int, buffer: Array[T]) {
  private var tail: Long = 0L
  private var head: Long = 0L

  def this(capacity: Int) {
    this(RingBuffer.nextPowerOfTwo(capacity), Array.ofDim[T](RingBuffer.nextPowerOfTwo(capacity)))
  }

  def add(e: T): Unit = {
    val wrapPoint = tail - capacity
    if (head <= wrapPoint) {
      head = head + 1
    }

    val ind = (tail % capacity).toInt
    buffer(ind) = e
    tail = tail + 1
  }

  def entries: Array[T] = {
    var i    = head
    var j    = 0
    val copy = if (tail > capacity) Array.ofDim[T](capacity) else Array.ofDim[T](tail.toInt)
    while (i < tail) {
      copy(j) = buffer((i % capacity).toInt)
      j += 1
      i += 1
    }
    copy
  }

  override def toString =
    s"[$head - $tail]: ${buffer.mkString(",")}"
}
