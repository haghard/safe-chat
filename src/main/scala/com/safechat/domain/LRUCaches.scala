// Copyright (c) 2019-2020 Vadim Bondarev. All rights reserved.

package com.safechat
package domain

import scala.collection.mutable

/** One more interesting implementation is akka.remote.artery.LruBoundedCache
  * https://www.sebastiansylvan.com/post/robin-hood-hashing-should-be-your-default-hash-table-implementation/
  *
  * ZIO stm based LRUCache
  * https://scalac.io/how-to-write-a-completely-lock-free-concurrent-lru-cache-with-zio-stm/
  *
  * Check them both out
  */
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

  /*
      Synchronization at Object level.
      Every read/write operation needs to acquire lock. Locking the entire collection is a performance overhead.
      This essentially gives access to only one thread to the entire map & blocks all the other threads.
      It may cause contention.
      SynchronizedHashMap returns Iterator, which fails-fast on concurrent modification.

      https://medium.com/@itsromiljain/curious-case-of-concurrenthashmap-90249632d335

      https://www.codewalk.com/2012/04/least-recently-used-lru-cache-implementation-java.html

      Concurrent version
      java.util.Collections.synchronizedMap(new LRULinkedHashMapCache[String, Int](1 << 3))
   */
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

  class Node[K, V](
    var previous: Node[K, V] = null, // Option[Node[K, V]]
    var next: Node[K, V] = null,
    val key: K = null.asInstanceOf[K],
    val value: V = null.asInstanceOf[V]
  ) {

    override def toString = {
      val prevK = if (previous != null) s"<-: ${previous.key}" else "<-: null"
      val nextK = if (next != null) s"->: ${next.key}" else "->: null"
      s"[$prevK:$nextK] - $key"
    }
  }

  /** For implementing an efficient LRU Cache (meaning get and put operations are executed in O(1) time), we could use two data structures:
    * Hash Map: containing (key, value) pairs.
    * Doubly linked list: which will contain the history of referenced keys. The Most Recently Used key will be at the start of the list, and the Least Recently Used one will be at the end.
    */
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
          if (currentSize == 0)
            leastRU = newNode
          currentSize += 1
        }
      }

    def size: Int = currentSize

    def show: Unit = {
      def loop(n: Node[K, V]): Unit = {
        println(n)
        if (n.next ne null) loop(n.next) else ()
      }
      loop(leastRU)
    }

    override def toString: String = {
      def loopMap(it: java.util.Iterator[K], sb: mutable.StringBuilder, first: Boolean = false): String =
        if (it.hasNext)
          if (first) loopMap(it, sb.append(it.next))
          else loopMap(it, sb.append(",").append(it.next))
        else sb.toString

      def loopList(n: Node[K, V], sb: mutable.StringBuilder): String = {
        val buf =
          if (n.next != null || n.previous != null) sb.append(n.key).append(",") else sb
        if (n.next != null) loopList(n.next, buf)
        else sb.append(" - ").toString
      }

      loopList(leastRU, new mutable.StringBuilder().append("list:")) +
      loopMap(nodes.keySet.iterator, new mutable.StringBuilder().append("map:"), true)
    }
  }
}
