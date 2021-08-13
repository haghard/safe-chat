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

sealed trait Mut[F[_], In] {
  def update(state: UserState)(args: In): UserState
}

//https://github.com/softwaremill/quicklens

//Mutators specialized for UserState
object Mut {

  type Id[T] = T

  implicit object SetUser extends Mut[Id, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.id).setTo(args)
  }

  implicit object AddSibling extends Mut[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ + args)
  }

  implicit object RmSibling extends Mut[Set, UserId] {
    def update(state: UserState)(args: UserId): UserState =
      state.modify(_.siblings).using(_ - args)
  }

  /*Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)]*/
  //implicit object AddPermition extends Op[Map[UserId, ?], (UserId, String)] {
  implicit object AddPermition extends Mut[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] {
    def update(state: UserState)(args: (UserId, String)): UserState = {
      val userId = args._1
      val p      = args._2
      state.modify(_.permisions).using(_ + (userId → p))
      //scala.util.Try(s.modify(_.permisions.at(userId)).setTo(p)).getOrElse(s)
    }
  }
}

//Describes targeted changes
sealed trait Patch[State] { self ⇒
  def +(that: Patch[State]): Patch[State] =
    Patch.Both(self, that) //Mod.Both(that, self)
}

object Patch {
  type Id[T] = T

  final case class Both[State](a: Patch[State], b: Patch[State]) extends Patch[State]

  //These patches go to the journal instead of events
  final case class SetUserId(id: String, M: Mut[Id, UserId] = Mut.SetUser)          extends Patch[UserState]
  final case class AddSiblingId(id: String, M: Mut[Set, UserId] = Mut.AddSibling)   extends Patch[UserState]
  final case class RemoveSiblingId(id: String, M: Mut[Set, UserId] = Mut.RmSibling) extends Patch[UserState]
  final case class AddUserPermitions(
    id: String,
    permision: String,
    M: Mut[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)] = Mut.AddPermition
    //OP: Op[Map[UserId, ?], (UserId, String)] = Op.AddPermition
  ) extends Patch[UserState]
}

object Example extends App {
  import Patch._

  def compile(patch: Patch[UserState]): UserState ⇒ UserState = { state ⇒ eval(state, patch) }
  def compileRec(patch: Patch[UserState]): UserState ⇒ UserState = { state ⇒ evalRec(state, patch).result }

  def setUserId(id: String): Patch[UserState]          = SetUserId(id)
  def addSibling(id: String): Patch[UserState]         = AddSiblingId(id)
  def rmSibling(id: String): Patch[UserState]          = RemoveSiblingId(id)
  def addPerm(id: String, p: String): Patch[UserState] = AddUserPermitions(id, p)

  def evalRec(state: UserState, U: Patch[UserState]): TailRec[UserState] =
    U match {
      case Both(a, b)           ⇒ tailcall(evalRec(state, a)).flatMap(s ⇒ evalRec(s, b))
      case m: SetUserId         ⇒ done(m.M.update(state)(UserId(m.id)))
      case m: AddSiblingId      ⇒ done(m.M.update(state)(UserId(m.id)))
      case m: RemoveSiblingId   ⇒ done(m.M.update(state)(UserId(m.id)))
      case m: AddUserPermitions ⇒ done(m.M.update(state)((UserId(m.id), m.permision)))
    }

  val maxStackSize = 8 //20000

  //apples in the reverse order
  def evalOptimized(
    state: UserState,
    mod: Patch[UserState],
    acc: ArrayBuffer[Patch[UserState]] = new ArrayBuffer[Patch[UserState]]
  ): UserState = {

    def evalState(state: UserState, acc: ArrayBuffer[Patch[UserState]]): UserState = {
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
    mod: Patch[UserState],
    acc: List[Patch[UserState]] = Nil
  ): UserState = {

    def evalState(state: UserState, acc: List[Patch[UserState]]): UserState = {
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

  def eval(state: UserState, m: Patch[UserState]): UserState =
    m match {
      //if (next.isEmpty) eval(state, a, Some(b)) else eval(state, next.get, None)
      case Both(both, single) ⇒
        //reverse order
        //eval(eval(state, single), both)
        //direct order
        eval(eval(state, both), single)
      //stackoverflow
      case m: SetUserId ⇒ m.M.update(state)(UserId(m.id))
      case m: AddSiblingId ⇒
        println("add" + m.id)
        m.M.update(state)(UserId(m.id))
      case m: RemoveSiblingId   ⇒ m.M.update(state)(UserId(m.id))
      case m: AddUserPermitions ⇒ m.M.update(state)((UserId(m.id), m.permision))
    }

  //java.lang.StackOverflowError
  val ops = List.range(1, 15).foldLeft(setUserId("0"))((acc, c) ⇒ acc + addSibling(c.toString))

  //val ops: Patch[UserState] = setUserId("9") + addSibling("1") + addSibling("2") + addPerm("2", "all") + rmSibling("1")

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
