package ch.epfl.vlsc.hls.backend.kernel;

import ch.epfl.vlsc.hls.backend.VivadoHLSBackend;
import ch.epfl.vlsc.platformutils.Emitter;
import ch.epfl.vlsc.platformutils.PathUtils;
import ch.epfl.vlsc.platformutils.utils.TypeUtils;
import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.ir.entity.PortDecl;
import se.lth.cs.tycho.ir.network.Network;
import se.lth.cs.tycho.type.Type;

@Module
public interface KernelWrapper {
    @Binding(BindingKind.INJECTED)
    VivadoHLSBackend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default void getKernelWrapper() {
        // -- Identifier
        String identifier = backend().task().getIdentifier().getLast().toString();

        // -- Network
        Network network = backend().task().getNetwork();

        // -- Network file
        emitter().open(PathUtils.getTargetCodeGenRtl(backend().context()).resolve(identifier + "_wrapper.sv"));

        emitter().emit("`default_nettype none");
        emitter().emit("`define STAGE_DONE 4");
        emitter().emitNewLine();

        // -- Network wrappermodule
        emitter().emit("module %s_wrapper #(", identifier);
        {
            emitter().increaseIndentation();

            getParameters(network);

            emitter().decreaseIndentation();
        }
        emitter().emit(")");
        emitter().emit("(");
        {
            emitter().increaseIndentation();

            getModulePortNames(network);

            emitter().decreaseIndentation();
        }
        emitter().emit(");");
        emitter().emitNewLine();

        // -- Time unit and precision
        emitter().emit("timeunit 1ps;");
        emitter().emit("timeprecision 1ps;");
        emitter().emitNewLine();

        // -- Wires and variables
        getWiresAndVariables(network);

        // -- RTL Body
        emitter().emitClikeBlockComment("Begin RTL Body");
        emitter().emitNewLine();

        // -- AP Logic
        getApLogic(network);

        // -- Input Stage(s)
        for (PortDecl port : network.getInputPorts()) {
            getInputStage(port);
        }

        // -- Network
        getNetwork(network);

        // -- Output Stage(s)
        for (PortDecl port : network.getOutputPorts()) {
            getOutputStage(port);
        }

        emitter().emit("endmodule : %s_wrapper", identifier);
        emitter().emit("`default_nettype wire");

        emitter().close();
    }

    // ------------------------------------------------------------------------
    // -- Parameters
    default void getParameters(Network network) {

        for (PortDecl port : network.getInputPorts()) {
            Type type = backend().types().declaredPortType(port);
            int bitSize = TypeUtils.sizeOfBits(type);
            boolean lastElement = network.getOutputPorts().isEmpty() && (network.getInputPorts().size() - 1 == network.getInputPorts().indexOf(port));
            emitter().emit("parameter integer C_M_AXI_%s_ADDR_WIDTH = %d,", port.getName().toUpperCase(), AxiConstants.C_M_AXI_ADDR_WIDTH);
            emitter().emit("parameter integer C_M_AXI_%s_DATA_WIDTH = %d,", port.getName().toUpperCase(), Math.max(bitSize, 32));
            emitter().emit("parameter integer C_M_AXI_%s_ID_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_AWUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_ARUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_WUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_RUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_BUSER_WIDTH =  %d%s", port.getName().toUpperCase(), 1, lastElement ? "" : ",");


        }
        for (PortDecl port : network.getOutputPorts()) {
            Type type = backend().types().declaredPortType(port);
            int bitSize = TypeUtils.sizeOfBits(type);
            emitter().emit("parameter integer C_M_AXI_%s_ADDR_WIDTH = %d,", port.getName().toUpperCase(), AxiConstants.C_M_AXI_ADDR_WIDTH);
            emitter().emit("parameter integer C_M_AXI_%s_DATA_WIDTH = %d,", port.getName().toUpperCase(), Math.max(bitSize, 32));
            emitter().emit("parameter integer C_M_AXI_%s_ID_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_AWUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_ARUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_WUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_RUSER_WIDTH = %d,", port.getName().toUpperCase(), 1);
            emitter().emit("parameter integer C_M_AXI_%s_BUSER_WIDTH =  %d%s", port.getName().toUpperCase(), 1, network.getOutputPorts().size() - 1 == network.getOutputPorts().indexOf(port) ? "" : ",");

        }
    }

    // ------------------------------------------------------------------------
    // -- Module port names
    default void getModulePortNames(Network network) {
        // -- System signals
        emitter().emit("input   wire    ap_clk,");
        emitter().emit("input   wire    ap_rst_n,");

        // -- Network input ports
        if (!network.getInputPorts().isEmpty()) {
            for (PortDecl port : network.getInputPorts()) {
                backend().topkernel().getAxiMasterPorts(port.getName());
            }
        }

        // -- Network Output ports
        if (!network.getOutputPorts().isEmpty()) {
            for (PortDecl port : network.getOutputPorts()) {
                backend().topkernel().getAxiMasterPorts(port.getName());
            }
        }

        // -- SDX control signals
        emitter().emit("// -- SDx Control signals");
        for (PortDecl port : network.getInputPorts()) {
            emitter().emit("input  wire    [32 - 1 : 0]    %s_requested_size,", port.getName());
            emitter().emit("input  wire    [64 - 1 : 0]    %s_size,", port.getName());
            emitter().emit("input  wire    [64 - 1 : 0]    %s_buffer,", port.getName());
        }

        // -- Network Output ports
        for (PortDecl port : network.getOutputPorts()) {
            emitter().emit("input  wire    [32 - 1 : 0]    %s_available_size,", port.getName());
            emitter().emit("input  wire    [64 - 1 : 0]    %s_size,", port.getName());
            emitter().emit("input  wire    [64 - 1 : 0]    %s_buffer,", port.getName());
        }


        emitter().emit("input   wire    ap_start,");
        emitter().emit("output  wire    ap_ready,");
        emitter().emit("output  wire    ap_idle,");
        emitter().emit("output  wire    ap_done");
    }

    // ------------------------------------------------------------------------
    // -- Wires and Variables
    default void getWiresAndVariables(Network network) {
        emitter().emitClikeBlockComment("Wires and Variables");
        emitter().emitNewLine();
        // -- AP Control
        emitter().emit("// -- AP Control");
        emitter().emit("logic   ap_start_pulse;");
        emitter().emit("logic   ap_start_r = 1'b0;");
        emitter().emit("logic   ap_idle_r = 1'b1;");
        emitter().emit("logic   ap_ready_r = 1'b0;");
        emitter().emit("logic   ap_done_r = 1'b0;");
        emitter().emitNewLine();


        // -- Network I/O
        emitter().emit("// -- Network I/O for %s module", backend().task().getIdentifier().getLast().toString());
        for (PortDecl port : network.getInputPorts()) {
            Type type = backend().types().declaredPortType(port);
            int bitSize = TypeUtils.sizeOfBits(type);
            emitter().emit("wire    [%d:0] %s_din;", bitSize - 1, port.getName());
            emitter().emit("wire    %s_full_n;", port.getName());
            emitter().emit("wire    %s_write;", port.getName());
        }

        for (PortDecl port : network.getOutputPorts()) {
            Type type = backend().types().declaredPortType(port);
            int bitSize = TypeUtils.sizeOfBits(type);
            emitter().emit("wire    [%d:0] %s_dout;", bitSize - 1, port.getName());
            emitter().emit("wire    %s_empty_n;", port.getName());
            emitter().emit("wire    %s_read;", port.getName());
        }
        emitter().emit("wire    core_done;");

        // -- I/O Stage
        emitter().emit("// -- AP for I/O Stage", backend().task().getIdentifier().getLast().toString());
        for(PortDecl port : network.getInputPorts()){
            emitter().emit("wire    %s_input_stage_ap_start;", port.getName());
            emitter().emit("wire    %s_input_stage_ap_done;", port.getName());
            emitter().emit("wire    [31:0] %s_input_stage_ap_return;", port.getName());
        }
        for(PortDecl port : network.getOutputPorts()){
            emitter().emit("wire    %s_output_stage_ap_start;", port.getName());
            emitter().emit("wire    %s_output_stage_ap_done;", port.getName());
            emitter().emit("wire    [31:0] %s_output_stage_ap_return;", port.getName());
        }

        emitter().emitNewLine();

    }

    // ------------------------------------------------------------------------
    // -- AP Logic
    default void getApLogic(Network network) {
        // -- pulse ap_start
        emitter().emit("// -- Pulse ap_start");
        emitter().emit("always @(posedge ap_clk) begin");
        emitter().emit("\tap_start_r <= ap_start;");
        emitter().emit("end");
        emitter().emitNewLine();
        emitter().emit("assign ap_start_pulse = ap_start & ~ap_start_r;");
        emitter().emitNewLine();

        // -- ap_idle
        emitter().emit("// -- ap_idle");
        emitter().emit("always @(posedge ap_clk) begin");
        {
            emitter().increaseIndentation();

            emitter().emit("if (ap_rst_n == 1'b0)");
            emitter().emit("\tap_idle_r <= 1'b1;");
            emitter().emit("else");
            emitter().emit("\tap_idle_r <= ap_done ? 1'b1 : ap_start_pulse ? 1'b0 : ap_idle;");

            emitter().decreaseIndentation();
        }
        emitter().emit("end");
        emitter().emitNewLine();
        emitter().emit("assign ap_idle = ap_idle_r;");
        emitter().emitNewLine();

        // -- ap_ready
        emitter().emit("// -- ap_ready");
        emitter().emit("always @(posedge ap_clk) begin");
        {
            emitter().increaseIndentation();

            emitter().emit("if (ap_rst_n == 1'b0)");
            emitter().emit("\tap_ready_r <= 1'b1;");
            emitter().emit("else");
            emitter().emit("\tap_ready_r <= 1'b0;");

            emitter().decreaseIndentation();
        }
        emitter().emit("end");
        emitter().emitNewLine();
        emitter().emit("assign ap_ready = ap_ready_r;");
        emitter().emitNewLine();

        // -- ap_done
        emitter().emit("// -- ap_done");
        emitter().emit("always @(posedge ap_clk) begin");
        {
            emitter().increaseIndentation();

            emitter().emit("if (ap_rst_n == 1'b0)");
            emitter().emit("\tap_done_r <= 1'b0;");
            emitter().emit("else");
            emitter().emit("\tap_done_r <= 1'b0; // TODO");

            emitter().decreaseIndentation();
        }
        emitter().emit("end");
        emitter().emitNewLine();
        emitter().emit("assign ap_done = ap_done_r;");
        emitter().emitNewLine();
    }

    default void getAxiMasterConnections(PortDecl port) {
        emitter().emit(".m_axi_%s_AWVALID(m_axi_%s_AWVALID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWREADY(m_axi_%s_AWREADY),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWADDR(m_axi_%s_AWADDR),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWID(m_axi_%s_AWID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWLEN(m_axi_%s_AWLEN),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWSIZE(m_axi_%s_AWSIZE),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWBURST(m_axi_%s_AWBURST),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWLOCK(m_axi_%s_AWLOCK),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWCACHE(m_axi_%s_AWCACHE),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWPROT(m_axi_%s_AWPROT),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWQOS(m_axi_%s_AWQOS),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWREGION(m_axi_%s_AWREGION),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_AWUSER(m_axi_%s_AWUSER),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WVALID(m_axi_%s_WVALID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WREADY(m_axi_%s_WREADY),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WDATA(m_axi_%s_WDATA),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WSTRB(m_axi_%s_WSTRB),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WLAST(m_axi_%s_WLAST),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WID(m_axi_%s_WID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_WUSER(m_axi_%s_WUSER),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARVALID(m_axi_%s_ARVALID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARREADY(m_axi_%s_ARREADY),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARADDR(m_axi_%s_ARADDR),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARID(m_axi_%s_ARID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARLEN(m_axi_%s_ARLEN),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARSIZE(m_axi_%s_ARSIZE),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARBURST(m_axi_%s_ARBURST),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARLOCK(m_axi_%s_ARLOCK),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARCACHE(m_axi_%s_ARCACHE),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARPROT(m_axi_%s_ARPROT),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARQOS(m_axi_%s_ARQOS),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARREGION(m_axi_%s_ARREGION),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_ARUSER(m_axi_%s_ARUSER),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RVALID(m_axi_%s_RVALID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RREADY(m_axi_%s_RREADY),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RDATA(m_axi_%s_RDATA),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RLAST(m_axi_%s_RLAST),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RID(m_axi_%s_RID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RUSER(m_axi_%s_RUSER),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_RRESP(m_axi_%s_RRESP),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_BVALID(m_axi_%s_BVALID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_BREADY(m_axi_%s_BREADY),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_BRESP(m_axi_%s_BRESP),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_BID(m_axi_%s_BID),", port.getSafeName(), port.getName());
        emitter().emit(".m_axi_%s_BUSER(m_axi_%s_BUSER),", port.getSafeName(), port.getName());
    }

    // ------------------------------------------------------------------------
    // -- Input Stages instantiation

    default void getInputStage(PortDecl port) {
        emitter().emit("// -- Input stage for port : %s", port.getName());
        emitter().emitNewLine();
        emitter().emit("assign %s_input_stage_ap_start = (%1$s_input_stage_ap_return === `STAGE_DONE) ? 1'b0 : 1'b1;", port.getName());
        emitter().emitNewLine();

        emitter().emit("%s_input_stage #(", port.getName());
        {
            emitter().increaseIndentation();

            emitter().emit(".C_M_AXI_%s_ID_WIDTH( C_M_AXI_%s_ID_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_ADDR_WIDTH( C_M_AXI_%s_ADDR_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_DATA_WIDTH( C_M_AXI_%s_DATA_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_AWUSER_WIDTH( C_M_AXI_%s_AWUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_ARUSER_WIDTH( C_M_AXI_%s_ARUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_WUSER_WIDTH( C_M_AXI_%s_WUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_RUSER_WIDTH( C_M_AXI_%s_RUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_BUSER_WIDTH( C_M_AXI_%s_BUSER_WIDTH )", port.getSafeName().toUpperCase(), port.getName().toUpperCase());

            emitter().decreaseIndentation();
        }
        emitter().emit(")");
        emitter().emit("i_%s_input_stage(", port.getName());
        {
            emitter().increaseIndentation();
            // -- Ap control
            emitter().emit(".ap_clk(ap_clk),");
            emitter().emit(".ap_rst_n(ap_rst_n),");
            emitter().emit(".ap_start(%s_input_stage_ap_start),", port.getName());
            emitter().emit(".ap_done(%s_input_stage_ap_done),", port.getName());
            emitter().emit(".ap_idle(),");
            emitter().emit(".ap_ready(),");
            emitter().emit(".ap_return(%s_input_stage_ap_return),", port.getName());
            emitter().emit(".core_done(core_done),");
            // -- AXI Master
            getAxiMasterConnections(port);
            // -- Direct address
            emitter().emit(".%s_requested_size(%1$s_requested_size),", port.getName());
            emitter().emit(".%s_size_r(%1$s_size),", port.getName());
            emitter().emit(".%s_buffer(%1$s_buffer),", port.getName());
            // -- FIFO I/O
            emitter().emit(".%s_V_din(%1$s_din),", port.getName());
            emitter().emit(".%s_V_full_n(%1$s_full_n),", port.getName());
            emitter().emit(".%s_V_write(%1$s_write)", port.getName());
            emitter().decreaseIndentation();
        }
        emitter().emit(");");
        emitter().emitNewLine();
    }

    // ------------------------------------------------------------------------
    // -- Network instantiation
    default void getNetwork(Network network) {
        emitter().emit("%s i_%1$s(", backend().task().getIdentifier().getLast().toString());
        {
            emitter().increaseIndentation();
            for (PortDecl port : network.getInputPorts()) {
                emitter().emit(".%s_din(%1$s_din),", port.getName());
                emitter().emit(".%s_full_n(%1$s_full_n),", port.getName());
                emitter().emit(".%s_write(%1$s_write),", port.getName());
            }
            for (PortDecl port : network.getOutputPorts()) {
                emitter().emit(".%s_dout(%1$s_dout),", port.getName());
                emitter().emit(".%s_empty_n(%1$s_empty_n),", port.getName());
                emitter().emit(".%s_read(%1$s_read),", port.getName());
            }
            emitter().emit(".ap_clk(ap_clk),");
            emitter().emit(".ap_rst_n(ap_rst_n),");
            emitter().emit(".ap_start(ap_start_pulse),");
            emitter().emit(".ap_idle(),");
            emitter().emit(".ap_done(core_done)");
            emitter().decreaseIndentation();
        }
        emitter().emit(");");
    }


    // ------------------------------------------------------------------------
    // -- Output Stages instantiation
    default void getOutputStage(PortDecl port) {
        emitter().emit("// -- Output stage for port : %s", port.getName());
        emitter().emitNewLine();

        emitter().emit("assign %s_output_stage_ap_start = (%1$s_output_stage_ap_return === `STAGE_DONE) ? 1'b0 : 1'b1;", port.getName());
        emitter().emitNewLine();

        emitter().emit("%s_output_stage #(", port.getName());
        {
            emitter().increaseIndentation();

            emitter().emit(".C_M_AXI_%s_ID_WIDTH( C_M_AXI_%s_ID_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_ADDR_WIDTH( C_M_AXI_%s_ADDR_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_DATA_WIDTH( C_M_AXI_%s_DATA_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_AWUSER_WIDTH( C_M_AXI_%s_AWUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_ARUSER_WIDTH( C_M_AXI_%s_ARUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_WUSER_WIDTH( C_M_AXI_%s_WUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_RUSER_WIDTH( C_M_AXI_%s_RUSER_WIDTH ),", port.getSafeName().toUpperCase(), port.getName().toUpperCase());
            emitter().emit(".C_M_AXI_%s_BUSER_WIDTH( C_M_AXI_%s_BUSER_WIDTH )", port.getSafeName().toUpperCase(), port.getName().toUpperCase());

            emitter().decreaseIndentation();
        }
        emitter().emit(")");
        emitter().emit("i_%s_output_stage(", port.getName());
        {
            emitter().increaseIndentation();
            // -- Ap control
            emitter().emit(".ap_clk(ap_clk),");
            emitter().emit(".ap_rst_n(ap_rst_n),");
            emitter().emit(".ap_start(%s_output_stage_ap_start),", port.getName());
            emitter().emit(".ap_done(%s_output_stage_ap_done),", port.getName());
            emitter().emit(".ap_idle(),");
            emitter().emit(".ap_ready(),");
            emitter().emit(".ap_return(%s_output_stage_ap_return),", port.getName());
            emitter().emit(".core_done(core_done),");
            // -- AXI Master
            getAxiMasterConnections(port);
            // -- Direct address
            emitter().emit(".%s_available_size(%1$s_available_size),", port.getName());
            emitter().emit(".%s_size_r(%1$s_size),", port.getName());
            emitter().emit(".%s_buffer(%1$s_buffer),", port.getName());
            // -- FIFO I/O
            emitter().emit(".%s_V_dout(%1$s_dout),", port.getName());
            emitter().emit(".%s_V_empty_n(%1$s_empty_n),", port.getName());
            emitter().emit(".%s_V_read(%1$s_read)", port.getName());
            emitter().decreaseIndentation();
        }
        emitter().emit(");");
        emitter().emitNewLine();
    }

}
