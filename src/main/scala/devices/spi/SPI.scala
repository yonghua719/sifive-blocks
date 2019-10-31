// See LICENSE for license details.
package sifive.blocks.devices.spi

import Chisel._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomaticobjectmodel.DiplomaticObjectModelAddressing
import freechips.rocketchip.diplomaticobjectmodel.model.{OMComponent, OMRegister}
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalModuleTree, LogicalTreeNode}
import sifive.blocks.util.BasicBusBlocker

case class SPIAttachParams(
  spi: SPIParams,
  controlBus: TLBusWrapper,
  intNode: IntInwardNode,
  blockerAddr: Option[BigInt] = None,
  controlXType: ClockCrossingType = NoCrossing,
  intXType: ClockCrossingType = NoCrossing,
  mclock: Option[ModuleValue[Clock]] = None,
  mreset: Option[ModuleValue[Bool]] = None,
  parentLogicalTreeNode: Option[LogicalTreeNode] = None)
  (implicit val p: Parameters)

case class SPIFlashAttachParams(
  spi: SPIFlashParams,
  controlBus: TLBusWrapper,
  memBus: TLBusWrapper,
  intNode: IntInwardNode,
  fBufferDepth: Int = 0,
  blockerAddr: Option[BigInt] = None,
  controlXType: ClockCrossingType = NoCrossing,
  intXType: ClockCrossingType = NoCrossing,
  memXType: ClockCrossingType = NoCrossing,
  mclock: Option[ModuleValue[Clock]] = None,
  mreset: Option[ModuleValue[Bool]] = None,
  parentLogicalTreeNode: Option[LogicalTreeNode] = None)
  (implicit val p: Parameters)

object SPI {
  val nextId = { var i = -1; () => { i += 1; i} }
  def attach(params: SPIAttachParams): TLSPI = {
    implicit val p = params.p
    val name = s"spi_${nextId()}"
    val cbus = params.controlBus
    val spi = LazyModule(new TLSPI(cbus.beatBytes, params.spi))
    spi.suggestName(name)

    cbus.coupleTo(s"device_named_$name") { bus =>
      val blockerNode = params.blockerAddr.map(BasicBusBlocker(_, cbus, cbus.beatBytes, name))
      (spi.controlXing(params.controlXType)
        := TLFragmenter(cbus)
        := blockerNode.map { _ := bus } .getOrElse { bus })
    }

    params.intNode := spi.intXing(params.intXType)

    InModuleBody { spi.module.clock := params.mclock.map(_.getWrappedValue).getOrElse(cbus.module.clock) }
    InModuleBody { spi.module.reset := params.mreset.map(_.getWrappedValue).getOrElse(cbus.module.reset) }

    params.parentLogicalTreeNode.foreach { parent =>
      LogicalModuleTree.add(parent, spi.logicalTreeNode)
    }
    spi
  }

  def attachAndMakePort(params: SPIAttachParams): ModuleValue[SPIPortIO] = {
    val spi = attach(params)
    val spiNode = spi.ioNode.makeSink()(params.p)
    InModuleBody { spiNode.makeIO()(ValName(spi.name)) }
  }

  val nextFlashId = { var i = -1; () => { i += 1; i} }
  def attachFlash(params: SPIFlashAttachParams): TLSPIFlash = {
    implicit val p = params.p
    val name = s"qspi_${nextFlashId()}" // TODO should these be shared with regular SPIs?
    val cbus = params.controlBus
    val mbus = params.memBus
    val qspi = LazyModule(new TLSPIFlash(cbus.beatBytes, params.spi))
    qspi.suggestName(name)

    cbus.coupleTo(s"device_named_$name") { bus =>
      val blockerNode = params.blockerAddr.map(BasicBusBlocker(_, cbus, cbus.beatBytes, name))
      (qspi.controlXing(params.controlXType)
        := TLFragmenter(cbus.beatBytes, cbus.blockBytes)
        := blockerNode.map { _ := bus } .getOrElse { bus })
    }

    mbus.coupleTo(s"mem_named_$name") { bus =>
      val blockerNode = params.blockerAddr.map(a => BasicBusBlocker(a+0x1000, cbus, mbus.beatBytes, name))
      (qspi.memXing(params.memXType)
        := TLFragmenter(1, mbus.blockBytes)
        := TLBuffer(BufferParams(params.fBufferDepth), BufferParams.none)
        := TLWidthWidget(mbus.beatBytes)
        := blockerNode.map { _ := bus } .getOrElse { bus })
    }

    params.intNode := qspi.intXing(params.intXType)

    InModuleBody { qspi.module.clock := params.mclock.map(_.getWrappedValue).getOrElse(cbus.module.clock) }
    InModuleBody { qspi.module.reset := params.mreset.map(_.getWrappedValue).getOrElse(cbus.module.reset) }

    params.parentLogicalTreeNode.foreach { parent =>
      LogicalModuleTree.add(parent, qspi.logicalTreeNode)
    }

    qspi
  }

  def attachAndMakePort(params: SPIFlashAttachParams): ModuleValue[SPIPortIO] = {
    val qspi = attachFlash(params)
    val qspiNode = qspi.ioNode.makeSink()(params.p)
    InModuleBody { qspiNode.makeIO()(ValName(qspi.name)) }
  }

  def connectPort(q: SPIPortIO): SPIPortIO = {
    val x = Wire(new SPIPortIO(q.c))
    x <> q
    x
  }
}
