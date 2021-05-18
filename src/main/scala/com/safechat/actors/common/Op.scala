package com.safechat.actors.common

import com.softwaremill.quicklens._

import scala.collection.mutable.ArrayBuffer
import scala.util.control.TailCalls._

final case class UserId(id: String)     extends AnyVal
final case class UserName(name: String) extends AnyVal
final case class User(id: UserId, name: UserName)

final case class UserState(
  id: UserId = UserId("-1"),
  siblings: Set[UserId] = Set.empty,
  permisions: Map[UserId, String] = Map.empty,
  counters: Map[UserId, Long] = Map.empty
)

sealed trait Op[F[_], In] {
  def update(state: UserState)(args: In): UserState
}

//https://github.com/softwaremill/quicklens

//Mutators specialized for UserState
object Op {

  type Id[T] = T

  implicit object SetUser extends Op[Id, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.id).setTo(args)
  }

  implicit object AddSibling extends Op[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ + args)
  }

  implicit object RmSibling extends Op[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ - args)
  }

  /*Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)]*/
  //implicit object AddPermition extends Op[Map[UserId, ?], (UserId, String)] {
  implicit object AddPermition extends Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] {
    def update(state: UserState)(args: (UserId, String)): UserState = {
      val userId = args._1
      val p      = args._2
      state.modify(_.permisions).using(_ + (userId → p))
      //scala.util.Try(s.modify(_.permisions.at(userId)).setTo(p)).getOrElse(s)
    }
  }
}

//Describes targeted changes
sealed trait Mod[State] { self ⇒
  def +(that: Mod[State]): Mod[State] =
    Mod.Both(self, that) //Mod.Both(that, self)
}

object Mod {
  type Id[T] = T

  final case class Both[State](a: Mod[State], b: Mod[State]) extends Mod[State]

  //these go to the journal instead of events
  //final case class Initial[State](s: State) extends Patch[State]

  final case class SetUserId(id: String, OP: Op[Id, UserId] = Op.SetUser)          extends Mod[UserState]
  final case class AddSiblingId(id: String, OP: Op[Set, UserId] = Op.AddSibling)   extends Mod[UserState]
  final case class RemoveSiblingId(id: String, OP: Op[Set, UserId] = Op.RmSibling) extends Mod[UserState]
  final case class AddUserPermitions(
    id: String,
    permision: String,
    OP: Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] = Op.AddPermition
    //OP: Op[Map[UserId, ?], (UserId, String)] = Op.AddPermition
  ) extends Mod[UserState]
}

object Example extends App {
  import Mod._

  def compile(patch: Mod[UserState]): UserState ⇒ UserState = { state ⇒ eval(state, patch) }
  def compileRec(patch: Mod[UserState]): UserState ⇒ UserState = { state ⇒ evalRec(state, patch).result }

  def setUserId(id: String): Mod[UserState]          = SetUserId(id)
  def addSibling(id: String): Mod[UserState]         = AddSiblingId(id)
  def rmSibling(id: String): Mod[UserState]          = RemoveSiblingId(id)
  def addPerm(id: String, p: String): Mod[UserState] = AddUserPermitions(id, p)

  def evalRec(state: UserState, U: Mod[UserState]): TailRec[UserState] =
    U match {
      case Both(a, b)           ⇒ tailcall(evalRec(state, a)).flatMap(s ⇒ evalRec(s, b))
      case m: SetUserId         ⇒ done(m.OP.update(state)(UserId(m.id)))
      case m: AddSiblingId      ⇒ done(m.OP.update(state)(UserId(m.id)))
      case m: RemoveSiblingId   ⇒ done(m.OP.update(state)(UserId(m.id)))
      case m: AddUserPermitions ⇒ done(m.OP.update(state)((UserId(m.id), m.permision)))
    }

  val maxStackSize = 8 //20000

  //apples in the reverse order
  def evalOptimized(
    state: UserState,
    mod: Mod[UserState],
    acc: ArrayBuffer[Mod[UserState]] = new ArrayBuffer[Mod[UserState]]
  ): UserState = {

    def evalState(state: UserState, acc: ArrayBuffer[Mod[UserState]]): UserState = {
      var cur = state
      acc.foreach { m ⇒
        cur = eval(cur, m)
      }
      cur
    }

    if (acc.size <= maxStackSize)
      mod match {
        case Both(next, leaf) ⇒ evalOptimized(state, next, acc :+ leaf)
        case last             ⇒ evalState(state, acc :+ last)
      }
    else {
      val s0 = evalState(state, acc)
      evalOptimized(s0, mod)
    }
  }

  //apples in direct order inside batches
  def evalOptimized2(
    state: UserState,
    mod: Mod[UserState],
    acc: List[Mod[UserState]] = Nil
  ): UserState = {

    def evalState(state: UserState, acc: List[Mod[UserState]]): UserState = {
      var cur = state
      //println(acc.mkString(" ,"))
      acc.foreach { m ⇒
        cur = eval(cur, m)
      }
      cur
    }

    mod match {
      case Both(both, leaf) ⇒
        if (acc.size <= maxStackSize) evalOptimized2(state, leaf, both :: acc)
        else {
          val s0 = evalState(state, acc)
          evalOptimized2(s0, mod, Nil)
        }
      case single ⇒
        acc.headOption match {
          case Some(value) ⇒
            value match {
              case both: Both[_] ⇒ evalOptimized2(state, both, single :: acc.tail)
              case _             ⇒ evalState(state, acc.head :: single :: acc.tail)
            }
          case None ⇒ evalState(state, single :: acc)
        }
    }
  }

  def eval(state: UserState, m: Mod[UserState]): UserState =
    m match {
      //if (next.isEmpty) eval(state, a, Some(b)) else eval(state, next.get, None)
      case Both(both, single) ⇒
        //reverse order
        //eval(eval(state, single), both)
        //direct order
        eval(eval(state, both), single)
      //stackoverflow
      case m: SetUserId ⇒ m.OP.update(state)(UserId(m.id))
      case m: AddSiblingId ⇒
        println("add" + m.id)
        m.OP.update(state)(UserId(m.id))
      case m: RemoveSiblingId   ⇒ m.OP.update(state)(UserId(m.id))
      case m: AddUserPermitions ⇒ m.OP.update(state)((UserId(m.id), m.permision))
    }

  //java.lang.StackOverflowError
  val ops = List.range(1, 15).foldLeft(setUserId("0"))((acc, c) ⇒ acc + addSibling(c.toString))

  //val ops: Mod[UserState] = setUserId("9") + addSibling("1") + addSibling("2") + addPerm("2", "all") + rmSibling("1")

  //val ops = List.range(2, 15).foldLeft(setUserId("1"))((acc, c) ⇒ acc + addSibling(c.toString))

  //eval(UserState(), ops)
  //evalOptimized(UserState(), ops)
  //evalRec(UserState(), ops)

  evalOptimized(UserState(), ops)
  //evalOptimized2(UserState(), ops)

  /*
  compile(ops)(UserState())
  compileRec(ops)(UserState())

  eval(UserState(), ops)
  evalRec(UserState(), ops).result*/
}
