// Copyright (c) 2019-2021 Vadim Bondarev. All rights reserved.

package com.safechat.domain

object RingBuffer {
  def nextPowerOfTwo(value: Int): Int =
    1 << (32 - Integer.numberOfLeadingZeros(value - 1))

  def apply[T: scala.reflect.ClassTag](capacity: Int) = new RingBuffer[T](capacity)
}

final class RingBuffer[T: scala.reflect.ClassTag] private (capacity: Int, buffer: Array[T]) {
  private var tail: Long = 0L
  private var head: Long = 0L

  private def this(capacity: Int) =
    this(RingBuffer.nextPowerOfTwo(capacity), Array.ofDim[T](RingBuffer.nextPowerOfTwo(capacity)))

  def add(e: T): Unit = :+(e)

  // append
  def :+(e: T): Unit = {
    val wrapPoint = tail - capacity
    if (head <= wrapPoint)
      head = head + 1

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
    s"RBuf([$head - $tail]: ${buffer.mkString(",")})"
}
