package com.safechat
package domain

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.{Deadline, FiniteDuration}

object LRUCache {

  def apply[K, V](capacity: Int) = {
    val empty = new Node[K, V]()
    new LRUCache[K, V](capacity, new java.util.HashMap[K, Node[K, V]](), empty, empty)
  }

  /*def create[K, V](capacity: Int) = {
    val empty = new Node[K, V]()
    new LRUCache0(capacity, new java.util.HashMap[K, Node[K, V]](), empty, empty)
  }*/

  def of[K, V](capacity: Int) =
    new LRULinkedHashMapCache[K, V](capacity)

  /*final class LRUCache[K, V](capacity: Int) {
    private val map        = new java.util.HashMap[K, V]()
    private val linkedList = new java.util.LinkedList[K]()

    def apply(key: K): Option[V] = {
      val targetValue = map.get(key)
      if (targetValue == null) None
      else {
        if (linkedList.peekLast == key) Some(targetValue)
        else {
          //O(n)
          linkedList.remove(key)
          linkedList.addLast(key)
          Some(targetValue)
        }
      }
    }

    def +=(key: K, value: V): Unit =
      put(key, value)

    def put(key: K, value: V): Unit =
      if (!map.containsKey(key)) {
        linkedList.addLast(key)
        map.put(key, value)

        // Delete the left-most entry and update the LRU pointer
        if (map.size == capacity + 1) {
          val evicted = linkedList.removeFirst
          map.remove(evicted)
        }
      }

    def size = map.size

    override def toString: String = {
      val it = linkedList.iterator
      val sb = new mutable.StringBuilder().append("list: ")
      while (it.hasNext) sb.append(it.next)
      sb.toString
    }
  }*/

  //https://www.codewalk.com/2012/04/least-recently-used-lru-cache-implementation-java.html
  final class LRULinkedHashMapCache[K, V](capacity: Int) extends java.util.LinkedHashMap[K, V](capacity, 1.0f, true) {

    def +=(key: K, value: V): Unit =
      put(key, value)

    //Returns true if this map should remove its eldest entry
    override protected def removeEldestEntry(eldest: java.util.Map.Entry[K, V]): Boolean =
      size() > capacity

    override def toString: String = {
      def loop(it: java.util.Iterator[K], sb: mutable.StringBuilder, first: Boolean = false): String =
        if (it.hasNext)
          if (first) loop(it, sb.append(it.next))
          else loop(it, sb.append(",").append(it.next))
        else sb.toString

      loop(keySet.iterator, new mutable.StringBuilder, true)
    }
  }

  class Node[T, U](
    var previous: Node[T, U] = null,
    var next: Node[T, U] = null,
    val key: T = null.asInstanceOf[T],
    val value: U = null.asInstanceOf[U]
  )

  class LRUCache[K, V](
    capacity: Int,
    nodes: java.util.Map[K, Node[K, V]],
    var leastRU: Node[K, V],
    var mostRU: Node[K, V]
  ) {

    private var currentSize: Int = 0

    //  O(1)
    def apply(key: K): Option[V] = {
      val targetNode = nodes.get(key)
      if (targetNode == null) None
      else if (targetNode.key == mostRU.key) Some(mostRU.value)
      else {
        val nextNode = targetNode.next
        val prevNode = targetNode.previous

        if (targetNode.key == leastRU.key) {
          nextNode.previous = null
          leastRU = nextNode
        } else {
          prevNode.next = nextNode
          nextNode.previous = prevNode
        }

        // Finally move our item to the MRU
        targetNode.previous = mostRU
        mostRU.next = targetNode
        mostRU = targetNode
        mostRU.next = null
        Some(targetNode.value)
      }
    }

    //  O(1)
    def +=(key: K, value: V): Unit =
      if (!nodes.containsKey(key)) {
        val newNode = new Node[K, V](mostRU, null, key, value)
        mostRU.next = newNode
        nodes.put(key, newNode)
        mostRU = newNode

        // Delete the left-most entry and update the LRU pointer
        if (capacity == currentSize) {
          nodes.remove(leastRU.key)
          leastRU = leastRU.next
          leastRU.previous = null
        } // Update cache size, for the first added entry update the LRU pointer
        else if (currentSize < capacity) {
          if (currentSize == 0) {
            leastRU = newNode
          }
          currentSize += 1
        }
      }

    def size: Int = currentSize

    override def toString: String = {
      def loopMap(it: java.util.Iterator[K], sb: mutable.StringBuilder, first: Boolean = false): String =
        if (it.hasNext)
          if (first) loopMap(it, sb.append(it.next))
          else loopMap(it, sb.append(",").append(it.next))
        else sb.toString

      def loopList(n: Node[K, V], sb: mutable.StringBuilder): String = {
        val buf =
          if (n.next != null || /*&&*/ n.previous != null) sb.append(n.key).append(",") else sb
        if (n.next != null) loopList(n.next, buf)
        else sb.append(" - ").toString
      }

      loopList(leastRU, new mutable.StringBuilder().append("list:")) +
      loopMap(nodes.keySet.iterator, new mutable.StringBuilder().append("map:"), true)
    }
  }

}
