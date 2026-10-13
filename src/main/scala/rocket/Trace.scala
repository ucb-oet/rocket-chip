// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import chisel3._
import eventtrace.EventTrace

/** What EventTrace records of Rocket's pipeline, in configs that trace: each instruction's pass from Decode until it
  * retires or is killed. A replay kills an instruction and everything younger and refetches them, so each pass is its
  * own object.
  */
object RocketTrace {

  /** The stage an instruction occupies. Decode is the instruction buffer's head, which no register holds. */
  object Stage extends ChiselEnum {
    val Decode, Execute, Memory, Writeback = Value
  }

  /** Why Rocket replays an instruction, by the stage that decides it: in Execute, `cache_busy`, `divider_busy`, and
    * `load_use` (an operand from a load that missed); in Memory, `writeback_port` and `fpu_nack`; in Writeback,
    * `cache_nack`, `csr_stall`, and `rocc_busy`.
    */
  object ReplayCause extends ChiselEnum {
    val cache_busy, divider_busy, load_use, writeback_port, fpu_nack, cache_nack, csr_stall, rocc_busy = Value
  }

  /** Why the instruction in Decode waits as its stall begins: the first term of `ctrl_stalld` that holds. A hazard
    * names the instruction in Execute, Memory, or Writeback whose result it needs; `scoreboard` waits on a
    * long-latency result whose instruction has retired.
    */
  object StallCause extends ChiselEnum {
    val execute_hazard, memory_hazard, writeback_hazard, scoreboard, fpu, cache_busy, divider_busy, fence, other = Value
  }

  /** One pass of a dynamic instruction through the pipeline. `bits` are as the commit log prints them, with a
    * compressed instruction in the low half.
    */
  class Instruction(pcBits: Int) extends EventTrace.ObjectType("instruction") {
    val pc    = const(UInt(pcBits.W))
    val bits  = const(UInt(32.W))
    val stage = mutable(Stage())
  }
}
