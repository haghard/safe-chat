package com.safechat.actors.common

import com.softwaremill.quicklens._

import scala.collection.mutable.ArrayBuffer

final case class UserId(id: String)     extends AnyVal
final case class UserName(name: String) extends AnyVal
final case class User(id: UserId, name: UserName)

final case class UserState(
  id: UserId = UserId("-1"),
  siblings: Set[UserId] = Set.empty,
  permission: Map[UserId, String] = Map.empty,
  counters: Map[UserId, Long] = Map.empty
)

//https://github.com/typelevel/kind-projector
//addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)

//Mutators|Diffs specialized for UserState
sealed trait Mutation[F[_], In] {
  def update(state: UserState)(args: In): UserState
}

//https://github.com/softwaremill/quicklens

//Mutators specialized for UserState
object Mutation {

  type Id[T] = T

  /*implicit*/
  object SetUser extends Mutation[Id, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.id).setTo(args)
  }

  /*implicit*/
  object AddSibling extends Mutation[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ + args)
  }

  /*implicit*/
  object RmSibling extends Mutation[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ - args)
  }

  /*Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)]*/
  // implicit object AddPermission extends Op[Map[UserId, ?], (UserId, String)] {
  // implicit object AddPermission extends Op[Map[UserId, *], (UserId, String)] {
  implicit object AddPermission extends Mutation[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] {
    def update(state: UserState)(args: (UserId, String)): UserState = {
      val userId = args._1
      val p      = args._2
      state.modify(_.permission).using(_ + (userId -> p))
      // scala.util.Try(s.modify(_.permisions.at(userId)).setTo(p)).getOrElse(s)
    }
  }
}

//Describes targeted changes
sealed trait Patch[State] { self =>
  def +(that: Patch[State]): Patch[State] = Patch.AtomicBatch(self, that)
}

//Describes targeted changes
object Patch {
  type Id[T] = T

  final case class AtomicBatch[State](a: Patch[State], b: Patch[State]) extends Patch[State]

  // These patches go to the journal instead of events !!!
  // TODO: mutation: replace Mutation[Id, UserId] with TypeTag(a Protoc type that represents a concrete mutation)
  final case class SetUserId(id: String, mutation: Mutation[Id, UserId] = Mutation.SetUser) extends Patch[UserState]
  final case class AddSiblingId(id: String, mutation: Mutation[Set, UserId] = Mutation.AddSibling)
      extends Patch[UserState]

  final case class RemoveSiblingId(id: String, mutation: Mutation[Set, UserId] = Mutation.RmSibling)
      extends Patch[UserState]

  final case class AddUserPermission(
    id: String,
    permission: String,
    mutation: Mutation[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] = Mutation.AddPermission
    // OP: Op[Map[UserId, ?], (UserId, String)] = Op.AddPermission
  ) extends Patch[UserState]
}

//runMain com.safechat.actors.common.Example
object Example extends App {
  import Patch._

  val maxStackSize = 1 << 10 // 20000

  def compile(patch: Patch[UserState]): UserState => UserState    = { state => eval(state, patch) }
  def compileRec(patch: Patch[UserState]): UserState => UserState = { state => evalRec(state, patch).result }

  def setUserId(id: String): Patch[UserState]          = SetUserId(id)
  def addSibling(id: String): Patch[UserState]         = AddSiblingId(id)
  def rmSibling(id: String): Patch[UserState]          = RemoveSiblingId(id)
  def addPerm(id: String, p: String): Patch[UserState] = AddUserPermission(id, p)

  /** https://blog.higher-order.com/
    */
  def evalRec(state: UserState, P: Patch[UserState]): scala.util.control.TailCalls.TailRec[UserState] = {
    import scala.util.control.TailCalls._
    P match {
      case AtomicBatch(a, b) =>
        tailcall(evalRec(state, a)).flatMap(s => evalRec(s, b))
      case m: SetUserId =>
        done(m.mutation.update(state)(UserId(m.id)))
      case m: AddSiblingId =>
        done(m.mutation.update(state)(UserId(m.id)))
      case m: RemoveSiblingId =>
        done(m.mutation.update(state)(UserId(m.id)))
      case m: AddUserPermission =>
        done(m.mutation.update(state)((UserId(m.id), m.permission)))
    }
  }

  // apples in the reverse order
  def evalOptimized(
    state: UserState,
    patch: Patch[UserState],
    acc: ArrayBuffer[Patch[UserState]] = new ArrayBuffer[Patch[UserState]]
  ): UserState = {

    def evalState(state: UserState, acc: ArrayBuffer[Patch[UserState]]): UserState = {
      var cur = state
      acc.foreach(m => cur = eval(cur, m))
      cur
    }

    if (acc.size <= maxStackSize)
      patch match {
        case AtomicBatch(next, leaf) => evalOptimized(state, next, acc :+ leaf)
        case last                    => evalState(state, acc :+ last)
      }
    else {
      val localState = evalState(state, acc)
      evalOptimized(localState, patch)
    }
  }

  // apples in direct order inside batches
  def evalOptimized2(
    state: UserState,
    mod: Patch[UserState],
    acc: List[Patch[UserState]] = Nil
  ): UserState = {

    def evalState(state: UserState, acc: List[Patch[UserState]]): UserState = {
      var cur = state
      // println(acc.mkString(" ,"))
      acc.foreach(m => cur = eval(cur, m))
      cur
    }

    mod match {
      case AtomicBatch(both, leaf) =>
        if (acc.size <= maxStackSize) evalOptimized2(state, leaf, both :: acc)
        else {
          val localState = evalState(state, acc)
          evalOptimized2(localState, mod, Nil)
        }
      case one =>
        acc.headOption match {
          case Some(value) =>
            value match {
              case both: AtomicBatch[_] => evalOptimized2(state, both, one :: acc.tail)
              case _                    => evalState(state, acc.head :: one :: acc.tail)
            }
          case None => evalState(state, one :: acc)
        }
    }
  }

  def eval(state: UserState, m: Patch[UserState]): UserState =
    m match {
      // if (next.isEmpty) eval(state, a, Some(b)) else eval(state, next.get, None)
      case AtomicBatch(both, single) =>
        // reverse order
        // eval(eval(state, single), both)
        // direct order
        eval(eval(state, both), single)
      // stackoverflow
      case m: SetUserId =>
        m.mutation.update(state)(UserId(m.id))

      case m: AddSiblingId =>
        println("add" + m.id)
        m.mutation.update(state)(UserId(m.id))

      case m: RemoveSiblingId   => m.mutation.update(state)(UserId(m.id))
      case m: AddUserPermission => m.mutation.update(state)((UserId(m.id), m.permission))
    }

  // val ops = List.range(2, 15).foldLeft(setUserId("1"))((acc, c) ⇒ acc + addSibling(c.toString))
  // val ops = List.range(1, 1_000_000).foldLeft(setUserId("0"))((acc, c) ⇒ acc + addSibling(c.toString))
  val ops: Patch[UserState] = setUserId("0") + addSibling("1") + addSibling("2") + addPerm("2", "all") + rmSibling("1")

  // val s = evalOptimized(UserState(), ops)
  // val s = evalOptimized2(UserState(), ops)

  // val s = compile(ops)(UserState()) //java.lang.StackOverflowError
  // val s = compileRec(ops)(UserState())

  // val s = eval(UserState(), ops) //java.lang.StackOverflowError
  // scala.util.control.TailCalls
  val s = evalRec(UserState(), ops).result
  println(s)
}
