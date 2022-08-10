package ch.epfl.vlsc.hls.backend.scripts;

import ch.epfl.vlsc.hls.backend.VivadoHLSBackend;
import ch.epfl.vlsc.platformutils.Emitter;
import ch.epfl.vlsc.platformutils.PathUtils;
import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.ir.network.Instance;
import se.lth.cs.tycho.ir.network.Network;

@Module
public interface VivadoTCL {

    @Binding(BindingKind.INJECTED)
    VivadoHLSBackend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default void generateVivadoTCL(){
        // -- Identifier
        String identifier = backend().task().getIdentifier().getLast().toString();

        // -- Network
        Network network = backend().task().getNetwork();

        // -- Network file
        emitter().open(PathUtils.getTargetScript(backend().context()).resolve(identifier + ".tcl.in"));

        emitter().emit("# -----------------------------------------------------------------------------");
        emitter().emit("# -- StreamBlocks Vivado Project TCL script");
        emitter().emitNewLine();

        // -- Create project
        createProject(identifier);

        // -- Import StreamBlocks Verilog RTL files
        importStreamblocksVerilogFiles(identifier);

        // -- Import Vivado HLS generated RTL files
        importVivadoHLSVerilogFiles(network);

        // -- Create&Import Vivado HLS Used IP Cores
        importVivadoHLSIpCres(network);

        // -- Import Simulation Verilog Modules
        importSimulationVerilogFiles(network);

        // -- Import Wcfg
        importWcfg(network);

        // -- Import XDC
        importXdc();

        // -- Set top module
        setTopModule(identifier);

        // -- Launch Synthesis
        launchSynthesis();

        emitter().close();
    }



    default void createProject(String identifier){
        emitter().emit("# -- Create project");
        emitter().emit("create_project %s @PROJECT_SOURCE_DIR@/output/%1$s -part @FPGA_NAME@ -force", identifier);
        emitter().emitNewLine();
    }

    default void importStreamblocksVerilogFiles(String identifier){
        emitter().emitSharpBlockComment("Import StreamBlocks Verilog RTL files");
        emitter().emit("import_files -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl/trigger_common.sv}");
        emitter().emit("import_files -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl/trigger.sv}");
        emitter().emit("import_files -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl/fifo.v}");
        emitter().emit("import_files -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl/%s.sv}", identifier);
        emitter().emit("import_files -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl/%s_pure.sv}", identifier);
        emitter().emitNewLine();
    }

    default void importVivadoHLSVerilogFiles(Network network){
        emitter().emitSharpBlockComment("Import Vivado HLS RTL files");

        for(Instance instance: network.getInstances()){
            String instanceId = instance.getInstanceName();
            emitter().emit("# -- Import files for instance : %s", instanceId);
            emitter().emit("set %s_files [glob -directory @CMAKE_CURRENT_BINARY_DIR@/%1$s/solution/syn/verilog *{v,dat}]", instanceId);
            emitter().emit("import_files -norecurse $%s_files", instanceId);
            emitter().emitNewLine();
        }

    }

    default void importVivadoHLSIpCres(Network network){
        emitter().emitSharpBlockComment("Import Vivado HLS Used IP Cores");

        for(Instance instance: network.getInstances()){
            String instanceId = instance.getInstanceName();
            emitter().emit("# -- Import IP Core for instance : %s", instanceId);
            emitter().emit("foreach script [glob -nocomplain [file join @CMAKE_CURRENT_BINARY_DIR@/%1$s/solution/syn/verilog *.tcl]] { source $script }", instanceId);
            emitter().emitNewLine();
        }

        emitter().emit("update_compile_order -fileset sources_1");
    }

    default void importSimulationVerilogFiles(Network network){
        emitter().emit("# -- Import Simulation modules");
        emitter().emit("set_property SOURCE_SET sources_1 [get_filesets sim_1]");
        // -- Identifier
        String identifier = backend().task().getIdentifier().getLast().toString();
        emitter().emit("import_files -fileset sim_1 -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl-tb/tb_%s.v}", identifier);
        for(Instance instance : network.getInstances()){
            String instanceId = instance.getInstanceName();
            emitter().emit("import_files -fileset sim_1 -norecurse {@PROJECT_SOURCE_DIR@/code-gen/rtl-tb/tb_%s.v}", instanceId);
        }
        emitter().emitNewLine();
    }


    default void importWcfg(Network network){
        emitter().emit("# -- Import WCFG Waveform");
        // -- Identifier
        String identifier = backend().task().getIdentifier().getLast().toString();
        emitter().emit("import_files -fileset sim_1 -norecurse {@PROJECT_SOURCE_DIR@/code-gen/wcfg/tb_%s_behav.wcfg}", identifier);
        // -- TODO : Add wcfg for instances
        emitter().emitNewLine();
    }

    default void importXdc(){
        emitter().emit("# -- Import XDC Clock constraint");
        emitter().emit("import_files -fileset constrs_1 -norecurse {@PROJECT_SOURCE_DIR@/code-gen/xdc/clock.xdc}");
        emitter().emitNewLine();
    }

    default void setTopModule(String identifier){
        emitter().emit("# -- Set top Simulation module ");
        // -- Identifier
        emitter().emit("set_property top tb_%s [get_filesets sim_1]", identifier);
        emitter().emit("set_property top_lib xil_defaultlib [get_filesets sim_1]");
        emitter().emit("update_compile_order -fileset sim_1");
        emitter().emitNewLine();
    }

    default void launchSynthesis(){
        emitter().emit("# -- Launch synthesis ");
        emitter().emit("launch_runs synth_1 -jobs 2");
        emitter().emit("wait_on_run synth_1");
        emitter().emitNewLine();

        emitter().emit("open_run synth_1 -name synth_1");
        emitter().emit("report_timing_summary -delay_type min_max -report_unconstrained -check_timing_verbose -max_paths 10 -input_pins -routable_nets -name timing_1 -file timing_summary.rpt ");
        emitter().emit("report_utilization -name utilization_1 -file report_utilization.rpt");
        emitter().emitNewLine();
        emitter().emit("close_design");
    }

}
