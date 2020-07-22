package ch.epfl.vlsc.hls.phase;

import ch.epfl.vlsc.hls.backend.VivadoHLSBackend;
import ch.epfl.vlsc.platformutils.ControllerToGraphviz;
import ch.epfl.vlsc.platformutils.PathUtils;
import ch.epfl.vlsc.settings.PlatformSettings;
import org.multij.MultiJ;
import se.lth.cs.tycho.compiler.CompilationTask;
import se.lth.cs.tycho.compiler.Compiler;
import se.lth.cs.tycho.compiler.Context;
import se.lth.cs.tycho.ir.decl.GlobalEntityDecl;
import se.lth.cs.tycho.ir.entity.Entity;
import se.lth.cs.tycho.ir.entity.PortDecl;
import se.lth.cs.tycho.ir.entity.am.ActorMachine;
import se.lth.cs.tycho.ir.network.Instance;
import se.lth.cs.tycho.ir.network.Network;
import se.lth.cs.tycho.ir.util.ImmutableList;
import se.lth.cs.tycho.phase.Phase;
import se.lth.cs.tycho.reporting.CompilationException;
import se.lth.cs.tycho.reporting.Diagnostic;
import se.lth.cs.tycho.reporting.Reporter;
import se.lth.cs.tycho.settings.Configuration;
import se.lth.cs.tycho.settings.Setting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class VivadoHLSBackendPhase implements Phase {

    /**
     * Root path of the compilation, only meaningful if partitioning is enabled.
     */
    private Path rootPath;
    /**
     * Target Path
     */
    private Path targetPath;

    /**
     * Code generation path
     */
    private Path codeGenPath;

    /**
     * Code generation path
     */
    private Path rtlPath;

    /**
     * code gen include directory
     */
    private Path codeGenIncludePath;
    /**
     * code gen source directory;
     */
    private Path codeGenSourcePath;
    /**
     * cmake path for finding the HLS tools
     */
    private Path cmakePath;

    /**
     * Script paths for hls generate by cmake
     */
    private Path scriptsPath;

    /**
     * Auxiliary Path
     */
    private Path auxiliaryPath;

    @Override
    public String getDescription() {
        return "StreamBlocks Vivado HLS Platform for Tycho compiler";
    }

    @Override
    public List<Setting<?>> getPhaseSettings() {

        return ImmutableList.of(
                PlatformSettings.scopeLivenessAnalysis,
                PlatformSettings.C99Host,
                PlatformSettings.arbitraryPrecisionIntegers,
                PlatformSettings.enableActionProfile,
                PlatformSettings.enableSystemC
        );
    }

    /**
     * Create the backend directories
     *
     * @param context
     */
    private void createDirectories(Context context) {

        // -- get the root path
        rootPath = context.getConfiguration().get(Compiler.targetPath).resolve("..");

        // -- get the target path
        targetPath = context.getConfiguration().get(Compiler.targetPath);
        // -- Cmake path
        cmakePath = PathUtils.createDirectory(targetPath, "cmake");

        // -- Script paths for cmake
        scriptsPath = PathUtils.createDirectory(targetPath, "scripts");

        // -- Code Generation paths
        codeGenPath = PathUtils.createDirectory(targetPath, "code-gen");

        // -- RTL path
        rtlPath = PathUtils.createDirectory(codeGenPath, "rtl");

        // -- Auxiliary path
        auxiliaryPath = PathUtils.createDirectory(codeGenPath, "auxiliary");

        // -- RTL testbench path
        PathUtils.createDirectory(codeGenPath, "rtl-tb");

        // -- Source path
        PathUtils.createDirectory(codeGenPath, "src");

        // -- Include path
        PathUtils.createDirectory(codeGenPath, "include");

        // -- Include testbench path
        PathUtils.createDirectory(codeGenPath, "include-tb");

        // -- Source testbench path
        PathUtils.createDirectory(codeGenPath, "src-tb");

        PathUtils.createDirectory(codeGenPath, "host");

        // -- WCFG path
        PathUtils.createDirectory(codeGenPath, "wcfg");

        // -- XDC path
        PathUtils.createDirectory(codeGenPath, "xdc");

        // -- Projects path
        PathUtils.createDirectory(codeGenPath, "tcl");

        // -- Build path
        PathUtils.createDirectory(targetPath, "build");

        // -- Output
        Path outputPath = PathUtils.createDirectory(targetPath, "output");

        // -- Fifo traces
        PathUtils.createDirectory(outputPath, "fifo-traces");

        // -- Kernel path
        PathUtils.createDirectory(outputPath, "kernel");
    }

    @Override
    public CompilationTask execute(CompilationTask task, Context context) throws CompilationException {

        // -- Get Reporter
        Reporter reporter = context.getReporter();
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, getDescription()));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Identifier, " + task.getIdentifier().toString()));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Target Path, " + PathUtils.getTarget(context)));

        // -- change the target path when partitioning is enabled
        if (context.getConfiguration().isDefined(PlatformSettings.PartitionNetwork) &&
                context.getConfiguration().get(PlatformSettings.PartitionNetwork)) {
            Path oldTargetPath = context.getConfiguration().get(Compiler.targetPath);
            Path newTargetPath = PathUtils.createDirectory(oldTargetPath, "vivado-hls");
            context.getConfiguration().set(Compiler.targetPath, newTargetPath);
        }

        // -- Create Directories
        createDirectories(context);


        // -- Instantiate backend, bind current compilation task and the context
        VivadoHLSBackend backend = MultiJ.from(VivadoHLSBackend.class)
                .bind("task").to(task)
                .bind("context").to(context)
                .instance();


        // -- Copy Backend resources
        copyBackendResources(backend);

        // -- Generate instances
        generateInstrances(backend);

        // -- Generate Globals
        generateGlobals(backend);

        // -- Generate devicehandle
        generateDeviceHandle(backend);

        // -- Generate Network
        generateNetwork(backend);

        // -- Generate Kernel
        generateKernel(backend);

        // -- Generate Testbenches
        generateTestbenches(backend);

        // -- Generate Project CMakeList
        generateCmakeLists(backend);

        // -- Generate Wcfg
        generateWcfg(backend);

        // -- Generate CMake Script
        generateCmakeScript(backend);

        // -- Generate Auxiliary
        generateAuxiliary(backend);

        return task;
    }

    /**
     * ReportInformation on Actor Machines to be generated
     *
     * @param task
     * @param reporter
     * @param backend
     */
    private void reportInfo(CompilationTask task, Reporter reporter, VivadoHLSBackend backend) {
        // -- Report on AM
        int nbrStates = 0;
        int maxInstanceState = 0;
        int nbrTransitions = 0;
        int nbrConditions = 0;

        for (Instance instance : task.getNetwork().getInstances()) {
            GlobalEntityDecl entityDecl = backend.globalnames().entityDecl(instance.getEntityName(), true);
            Entity entity = entityDecl.getEntity();
            if (entity instanceof ActorMachine) {
                ActorMachine am = (ActorMachine) entity;
                int instanceStateSize = am.controller().getStateList().size();
                nbrStates += instanceStateSize;
                if (instanceStateSize > maxInstanceState) {
                    maxInstanceState = instanceStateSize;
                }
                nbrTransitions += am.getTransitions().size();
                nbrConditions += am.getConditions().size();
            }
        }
        // -- Information
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Nbr of Instances: " + task.getNetwork().getInstances().size()));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Nbr of Queues: " + task.getNetwork().getConnections().size()));

        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Sum of States: " + nbrStates));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Sum of Conditions: " + nbrConditions));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Sum of Transitions: " + nbrTransitions));
        reporter.report(new Diagnostic(Diagnostic.Kind.INFO, "Maximum State of all instances: " + maxInstanceState));
    }


    /**
     * Generate Source code for instances
     *
     * @param backend
     */

    public static void generateInstrances(VivadoHLSBackend backend) {
        for (Instance instance : backend.task().getNetwork().getInstances()) {
            GlobalEntityDecl entityDecl = backend.globalnames().entityDecl(instance.getEntityName(), true);
            if (!entityDecl.getExternal()) {
                backend.instance().generateInstance(instance);
            }
        }
    }

    /**
     * Generate Verilog network
     *
     * @param backend
     */
    public static void generateNetwork(VivadoHLSBackend backend) {

        backend.vnetwork().generateNetwork();
        int nbrConnections = backend.task().getNetwork().getConnections().size();

        boolean systemCNetwork = backend.context().getConfiguration().isDefined(PlatformSettings.enableSystemC) &&
                backend.context().getConfiguration().get(PlatformSettings.enableSystemC);
        if (systemCNetwork) {
            backend.scnetwork().generateNetwork();
        }
    }

    /**
     * Generate Verilog network wrapper for OpenCL Kernel
     *
     * @param backend
     */
    private void generateKernel(VivadoHLSBackend backend) {
        // -- Top Kernel
        backend.topkernel().generateTopKernel();

        // -- AXI4-Lite Control
        backend.axilitecontrol().getAxiLiteControl();

        // -- Kernel Wrapper
        backend.kernelwrapper().getKernelWrapper();

        // -- Input Stage
        for (PortDecl port : backend.task().getNetwork().getInputPorts()) {
            backend.inputstagemem().getInputStageMem(port);
            backend.inputstage().getInputStage(port);
        }

        // -- Output Stage
        for (PortDecl port : backend.task().getNetwork().getOutputPorts()) {
            backend.outputstagemem().getOutputStageMem(port);
            backend.outputstage().getOutputStage(port);
        }

        // -- Kernel XML
        backend.kernelxml().getKernelXml();

        // -- TCL script for packaging the OpenCL RTL Kernel
        backend.packagekernel().getPackageKernel();
    }

    /**
     * Generate gloabals
     *
     * @param backend
     */
    public static void generateGlobals(VivadoHLSBackend backend) {

        // -- Globals Header
        backend.globals().globalHeader();
    }

    /**
     * Generate host DeviceHandle
     *
     * @param backend
     */
    public static void generateDeviceHandle(VivadoHLSBackend backend) {

        // -- Globals Header
        backend.devicehandle().generateDeviceHandle();
    }

    /**
     * Generate tesbenches for Network and Instances
     *
     * @param backend
     */
    public static void generateTestbenches(VivadoHLSBackend backend) {
        // -- Network
        Network network = backend.task().getNetwork();

        // -- Network Verilog Testbench
        backend.testbench().generateTestbench(network);

        // -- SystemC network testbench
        if (backend.context().getConfiguration().isDefined(PlatformSettings.enableSystemC)
            && backend.context().getConfiguration().get(PlatformSettings.enableSystemC)) {

            backend.sctester().generateTester();
            backend.simulator().genrateSimulator(network);
        }

        // -- Instance Verilog Testbench
        network.getInstances().forEach(backend.testbench()::generateTestbench);

        // -- Instance HLS Testbench
        network.getInstances().forEach(backend.testbenchHLS()::generateInstanceTestbench);
    }

    public static void generateWcfg(VivadoHLSBackend backend) {
        // -- Wcfg
        Network network = backend.task().getNetwork();
        backend.wcfg().getWcfg(network);
    }

    public static void generateCmakeScript(VivadoHLSBackend backend) {
        // -- CMake script for Vivado HLS
        backend.vivadotcl().generateVivadoTCL();
    }

    /**
     * Generates the various CMakeLists.txt for building the generated code
     *
     * @param backend
     */
    public static void generateCmakeLists(VivadoHLSBackend backend) {
        // -- Project CMakeLists
        backend.cmakelists().projectCMakeLists();
    }

    public static void generateAuxiliary(VivadoHLSBackend backend) {

        // -- Network to DOT
        backend.netoworkToDot().generateNetworkDot();

        // -- Actor Machine Controllers to DOT
        for (Instance instance : backend.task().getNetwork().getInstances()) {

            String instanceName = instance.getInstanceName();
            GlobalEntityDecl entityDecl = backend.globalnames().entityDecl(instance.getEntityName(), true);

            if (entityDecl.getEntity() instanceof ActorMachine) {
                ControllerToGraphviz dot = new ControllerToGraphviz(entityDecl, instanceName,
                        PathUtils.getAuxiliary(backend.context()).resolve(instanceName + ".dot"));
                dot.print();
            }
        }
    }

    /**
     * Copy the backend resources
     *
     * @param backend
     */
    private void copyBackendResources(VivadoHLSBackend backend) {
        try {
            // -- Vivado HLS Fifo
            Files.copy(getClass().getResourceAsStream("/lib/verilog/fifo.v"),
                    PathUtils.getTargetCodeGenRtl(backend.context()).resolve("fifo.v"),
                    StandardCopyOption.REPLACE_EXISTING);
            // -- Actor Start controller
            Files.copy(getClass().getResourceAsStream("/lib/verilog/TriggerTypes.sv"),
                    PathUtils.getTargetCodeGenRtl(backend.context()).resolve("TriggerTypes.sv"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/verilog/trigger.sv"),
                    PathUtils.getTargetCodeGenRtl(backend.context()).resolve("trigger.sv"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- Find Vivado hls, vivado & SDAccel for cmake
            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindVivadoHLS.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindVivadoHLS.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindVivado.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindVivado.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindSDAccel.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindSDAccel.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindXRT.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindXRT.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindVitis.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindVitis.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);

            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindSystemCLanguage.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindSystemCLanguage.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);

            Files.copy(getClass().getResourceAsStream("/lib/cmake/FindVerilator.cmake"),
                    PathUtils.getTargetCmake(backend.context()).resolve("FindVerilator.cmake"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- Input and Output Stage mem Header
            Files.copy(getClass().getResourceAsStream("/lib/hls/iostage.h"),
                    PathUtils.getTargetCodeGenInclude(backend.context()).resolve("iostage.h"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- Synthesis script for Vivado HLS as an input to CMake
            Files.copy(getClass().getResourceAsStream("/lib/cmake/Synthesis.tcl.in"),
                    PathUtils.getTargetScripts(backend.context()).resolve("Synthesis.tcl.in"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- Gen XO
            Files.copy(getClass().getResourceAsStream("/lib/cmake/gen_xo.tcl.in"),
                    PathUtils.getTargetScripts(backend.context()).resolve("gen_xo.tcl.in"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- sdaccel ini
            Files.copy(getClass().getResourceAsStream("/lib/cmake/sdaccel.ini.in"),
                    PathUtils.getTargetScripts(backend.context()).resolve("sdaccel.ini.in"),
                    StandardCopyOption.REPLACE_EXISTING);

            // -- systemc material
            Files.copy(getClass().getResourceAsStream("/lib/systemc/queue.h"),
                    PathUtils.getTargetCodeGenInclude(backend.context()).resolve("queue.h"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/systemc/trigger.h"),
                    PathUtils.getTargetCodeGenInclude(backend.context()).resolve("trigger.h"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getClass().getResourceAsStream("/lib/systemc/sim_iostage.h"),
                    PathUtils.getTargetCodeGenInclude(backend.context()).resolve("sim_iostage.h"),
                    StandardCopyOption.REPLACE_EXISTING);

            Files.copy(getClass().getResourceAsStream("/lib/systemc/debug_macros.h"),
                    PathUtils.getTargetCodeGenInclude(backend.context()).resolve("debug_macros.h"),
                    StandardCopyOption.REPLACE_EXISTING);


        } catch (IOException e) {
            throw new CompilationException(new Diagnostic(Diagnostic.Kind.ERROR, "Could not copy backend resources"));
        }
    }

    public Path getCodeGenIncludePath() {
        return codeGenIncludePath;
    }

    public Path getCodeGenSourcePath() {
        return codeGenSourcePath;
    }
}
