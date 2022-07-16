package com.safechat.actors.common

import com.softwaremill.quicklens._

import scala.collection.mutable.ArrayBuffer

/*
final case class UserId(id: String)     extends AnyVal
final case class UserName(name: String) extends AnyVal
 */

//https://youtu.be/WyvawRRuU2c
//https://github.com/plokhotnyuk/jsoniter-scala/blob/master/jsoniter-scala-macros/shared/src/test/scala/com/github/plokhotnyuk/jsoniter_scala/macros/JsonCodecMakerSpec.scala
object UsrId {
  type T = Base with Tag
  type Base = Any {
    type Hack
  }
  trait Tag
  object mk {
    def apply(value: Long): T            = value.asInstanceOf[T]
    def unapply(userId: T): Option[Long] = Option(userId).map(_.value)
  }
  final implicit class Ops(private val userId: T) extends AnyVal {
    def value: Long = userId.asInstanceOf[Long]
  }
}

object UsrName {
  type T = Base with Tag
  type Base = Any {
    type Hack
  }
  trait Tag
  object make {
    def apply(name: String): T            = name.asInstanceOf[T]
    def unapply(value: T): Option[String] = Option(value).map(_.value)
  }
  final implicit class Ops(private val name: T) extends AnyVal {
    def value: String = name.asInstanceOf[String]
  }
}

object Perm {
  type T = Base with Tag
  type Base = Any {
    type Hack
  }
  trait Tag
  object mk {
    def apply(permission: String): T      = permission.asInstanceOf[T]
    def unapply(value: T): Option[String] = Option(value).map(_.value)
  }
  final implicit class Ops(private val permission: T) extends AnyVal {
    def value: String = permission.asInstanceOf[String]
  }
}

final case class User(usr: UsrId.T, name: UsrName.T)

final case class UserState(
  id: UsrId.T = UsrId.mk(-1),
  siblings: Set[UsrId.T] = Set.empty,
  usrPermissions: Map[UsrId.T, Perm.T] = Map.empty
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
  object SetUser extends Mutation[Id, UsrId.T] {
    def update(state: UserState)(arg: UsrId.T): UserState =
      state.modify(_.id).setTo(arg)
  }

  /*implicit*/
  object AddSibling extends Mutation[Set, UsrId.T] {
    def update(state: UserState)(args: UsrId.T): UserState =
      state.modify(_.siblings).using(_ + args)
  }

  /*implicit*/
  object RmSibling extends Mutation[Set, UsrId.T] {
    def update(state: UserState)(args: UsrId.T): UserState =
      state.modify(_.siblings).using(_ - args)
  }

  /*Op[({ type UserIdMap[A] = Map[UserId, A] })#UserIdMap, (UserId, String)]*/
  // implicit object AddPermission extends Op[Map[UserId, ?], (UserId, String)] {
  // implicit object AddPermission extends Op[Map[UserId, *], (UserId, String)] {
  implicit object AddPermission
      extends Mutation[({ type UserIdMap[A] = Map[UsrId.T, A] })#UserIdMap, (UsrId.T, Perm.T)] {
    def update(state: UserState)(args: (UsrId.T, Perm.T)): UserState = {
      val userId = args._1
      val p      = args._2
      state.modify(_.usrPermissions).using(_ + (userId -> p))
      // scala.util.Try(s.modify(_.permisions.at(userId)).setTo(p)).getOrElse(s)
    }
  }
}

//Describes targeted changes
sealed trait Patch[State] { self =>
  def +(that: Patch[State]): Patch[State] = Patch.Both(self, that)
}

//Describes targeted changes
object Patch {
  type Id[T] = T

  final case class Both[State](a: Patch[State], b: Patch[State]) extends Patch[State]

  // These patches go to the journal instead of events !!!
  // TODO: mutation: replace Mutation[Id, UserId] with TypeTag(a Protoc type that represents a concrete mutation)
  final case class SetUserId(userId: UsrId.T, mutation: Mutation[Id, UsrId.T] = Mutation.SetUser)
      extends Patch[UserState]
  final case class AddSiblingId(sibId: UsrId.T, mutation: Mutation[Set, UsrId.T] = Mutation.AddSibling)
      extends Patch[UserState]

  final case class RemoveSiblingId(sibId: UsrId.T, mutation: Mutation[Set, UsrId.T] = Mutation.RmSibling)
      extends Patch[UserState]

  final case class AddUserPermission(
    userId: UsrId.T,
    permission: Perm.T,
    mutation: Mutation[({ type UserIdMap[A] = Map[UsrId.T, A] })#UserIdMap, (UsrId.T, Perm.T)] = Mutation.AddPermission
    // OP: Op[Map[UserId, ?], (UserId, String)] = Op.AddPermission
  ) extends Patch[UserState]

  // constructors
  def setUserId(usr: UsrId.T): Patch[UserState]          = SetUserId(usr)
  def addSibling(sib: UsrId.T): Patch[UserState]         = AddSiblingId(sib)
  def rmSibling(sib: UsrId.T): Patch[UserState]          = RemoveSiblingId(sib)
  def addPerm(usr: UsrId.T, p: Perm.T): Patch[UserState] = AddUserPermission(usr, p)
}

//runMain com.safechat.actors.common.Example
object Example extends App {
  import Patch._

  val maxStackSize = 1 << 10 // 20000

  def compile(patch: Patch[UserState]): UserState => UserState    = { state => eval(state, patch) }
  def compileRec(patch: Patch[UserState]): UserState => UserState = { state => evalRec(state, patch).result }

  /** https://blog.higher-order.com/
    */
  def evalRec(state: UserState, P: Patch[UserState]): scala.util.control.TailCalls.TailRec[UserState] = {
    import scala.util.control.TailCalls._
    P match {
      case Both(a, b)           => tailcall(evalRec(state, a)).flatMap(s => evalRec(s, b))
      case m: SetUserId         => done(m.mutation.update(state)(m.userId))
      case m: AddSiblingId      => done(m.mutation.update(state)(m.sibId))
      case m: RemoveSiblingId   => done(m.mutation.update(state)(m.sibId))
      case m: AddUserPermission => done(m.mutation.update(state)((m.userId, m.permission)))
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
        case Both(next, leaf) => evalOptimized(state, next, acc :+ leaf)
        case last             => evalState(state, acc :+ last)
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
      case Both(both, leaf) =>
        if (acc.size <= maxStackSize) evalOptimized2(state, leaf, both :: acc)
        else {
          val localState = evalState(state, acc)
          evalOptimized2(localState, mod, Nil)
        }
      case one =>
        acc.headOption match {
          case Some(value) =>
            value match {
              case both: Both[_] => evalOptimized2(state, both, one :: acc.tail)
              case _             => evalState(state, acc.head :: one :: acc.tail)
            }
          case None => evalState(state, one :: acc)
        }
    }
  }

  def eval(state: UserState, m: Patch[UserState]): UserState =
    m match {
      // if (next.isEmpty) eval(state, a, Some(b)) else eval(state, next.get, None)
      case Both(both, single) =>
        // reverse order
        // eval(eval(state, single), both)
        // direct order
        eval(eval(state, both), single)
      // stackoverflow
      case m: SetUserId =>
        println(s"SetUserId(${m.userId})")
        m.mutation.update(state)(m.userId)

      case m: AddSiblingId =>
        println(s"AddSiblingId(${m.sibId})")
        m.mutation.update(state)(m.sibId)

      case m: RemoveSiblingId =>
        println(s"RemoveSiblingId(${m.sibId})")
        m.mutation.update(state)(m.sibId)

      case m: AddUserPermission =>
        println(s"AddUserPermission(${m.userId})")
        m.mutation.update(state)((m.userId, m.permission))
    }

  // val ops = List.range(2, 15).foldLeft(setUserId("1"))((acc, c) ⇒ acc + addSibling(c.toString))
  // val ops = List.range(1, 1_000_000).foldLeft(setUserId("0"))((acc, c) ⇒ acc + addSibling(c.toString))

  /*
  val adds = List.range(1, 1_000_000).foldLeft(setUserId("0"))((acc, c) => acc + addSibling(c.toString))
  val ops: Patch[UserState] = List.range(1, 999_995).foldLeft(adds)((acc, c) => acc + rmSibling(c.toString))
   */

  val ops: Patch[UserState] = setUserId(UsrId.mk(0L)) + addSibling(UsrId.mk(1L)) +
    addSibling(UsrId.mk(2)) + addPerm(UsrId.mk(2), Perm.mk("all")) + rmSibling(UsrId.mk(1))

  // val s = evalOptimized(UserState(), ops)
  // val s = evalOptimized2(UserState(), ops)

  // val s = compile(ops)(UserState()) //java.lang.StackOverflowError
  // val s = compileRec(ops)(UserState())

  // val s = eval(UserState(), ops) //java.lang.StackOverflowError
  // scala.util.control.TailCalls
  val s = evalRec(UserState(), ops).result
  println(s)
}
