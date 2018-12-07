package com.streamgenomics.backend.cpp;

import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.attribute.Types;
import se.lth.cs.tycho.ir.decl.GlobalEntityDecl;
import se.lth.cs.tycho.ir.decl.VarDecl;
import se.lth.cs.tycho.ir.entity.Entity;
import se.lth.cs.tycho.ir.entity.PortDecl;
import se.lth.cs.tycho.ir.entity.am.*;
import se.lth.cs.tycho.ir.network.Connection;
import se.lth.cs.tycho.type.CallableType;
import se.lth.cs.tycho.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Module
public interface Structure {
    @Binding(BindingKind.INJECTED)
    Backend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default Preprocessor preprocessor() {
        return backend().preprocessor();
    }

    default Code code() {
        return backend().code();
    }

    default Types types() {
        return backend().types();
    }

    default DefaultValues defVal() {
        return backend().defaultValues();
    }

    default void actorHdr(GlobalEntityDecl decl) {
        String name = backend().instance().get().getInstanceName();
        actorHeader(name, decl.getEntity());
    }

    default void actorDecl(GlobalEntityDecl decl) {
        String name = backend().instance().get().getInstanceName();
        actor(name, decl.getEntity());
    }

    default void actorHeader(String name, Entity entity) {
    }

    default void actorHeader(String name, ActorMachine actorMachine) {
        preprocessor().userInclude("actor.h");
        preprocessor().userInclude("fifo.h");
        preprocessor().userInclude("fifo_list.h");

        emitter().emitNewLine();

        emitter().emit("class %s : public Actor {", name);
        emitter().emit("public :");
        emitter().increaseIndentation();

        actorMachineConstructor(name, actorMachine);

        actorMachineControllerHeader(name, actorMachine);

        emitter().decreaseIndentation();

        emitter().emit("private :");
        emitter().increaseIndentation();

        emitter().emit("// -- Scopes Initialization");
        actorMachineScopesInit(actorMachine);

        emitter().emit("// -- Actor Machine Transitions");
        actorMachineTransitionsPrototype(actorMachine);

        emitter().emit("// -- Actor Machine Conditions");
        actorMachineConditionsPrototypes(actorMachine);

        emitter().emit("// -- State Variables");
        actorMachineState(name, actorMachine);

        emitter().decreaseIndentation();

        emitter().emit("};");
    }

    default void actor(String name, Entity entity) {
    }

    default void actor(String name, ActorMachine actorMachine) {
        preprocessor().userIncludeActor(name);
        emitter().emitNewLine();

        actorMachineStateInit(name, actorMachine);
        actorMachineTransitions(name, actorMachine);
        actorMachineConditions(name, actorMachine);
        actorMachineController(name, actorMachine);
    }

    default void actorMachineControllerHeader(String name, ActorMachine actorMachine) {
        backend().controllers().emitControllerHeader(name, actorMachine);
        emitter().emit("");
    }

    default void actorMachineController(String name, ActorMachine actorMachine) {
        backend().controllers().emitController(name, actorMachine);
        emitter().emit("");
        emitter().emit("");
    }

    default void actorMachineInitHeader(String name, ActorMachine actorMachine) {
        emitter().emit("void init_actor();");
        emitter().emit("");
    }

    default void actorMachineConstructor(String name, ActorMachine actorMachine) {
        List<String> parameters = getEntityInitParameters(actorMachine);
        emitter().emit("%s(%s) {", name, String.join(", ", parameters));
        emitter().increaseIndentation();
        emitter().emit("this->program_counter = 0;");
        emitter().emit("");

        emitter().emit("// -- parameters");
        actorMachine.getValueParameters().forEach(d -> {
            emitter().emit("this->%s = %1$s;", backend().variables().declarationName(d));
        });
        emitter().emit("");

        emitter().emit("// -- input ports");
        actorMachine.getInputPorts().forEach(p -> {
            emitter().emit("this->%s_channel = %1$s_channel;", p.getName());
        });
        emitter().emit("");

        emitter().emit("// -- output ports");
        actorMachine.getOutputPorts().forEach(p -> {
            emitter().emit("this->%s_channels = %1$s_channels;", p.getName());
        });
        emitter().emit("");

        emitter().emit("// -- init persistent scopes");
        int i = 0;
        for (Scope s : actorMachine.getScopes()) {
            if (s.isPersistent()) {
                emitter().emit("init_scope_%d();", i);
            }
            i = i + 1;
        }
        emitter().decreaseIndentation();
        emitter().emit("}");
        emitter().emit("");
        emitter().emit("");
    }

    default List<String> getEntityInitParameters(Entity actorMachine) {
        List<String> parameters = new ArrayList<>();
        actorMachine.getValueParameters().forEach(d -> {
            parameters.add(code().declaration(types().declaredType(d), backend().variables().declarationName(d)));
        });
        actorMachine.getInputPorts().forEach(p -> {
            String type = backend().channels().targetEndTypeSize(new Connection.End(Optional.of(backend().instance().get().getInstanceName()), p.getName()));
            parameters.add(String.format("Fifo<%s> *%s_channel", type, p.getName()));
        });
        actorMachine.getOutputPorts().forEach(p -> {
            Connection.End source = new Connection.End(Optional.of(backend().instance().get().getInstanceName()), p.getName());
            String type = backend().channels().sourceEndTypeSize(source);
            parameters.add(String.format("FifoList<%s > *%s_channels", type, p.getName()));
        });
        return parameters;
    }

    default void actorMachineTransitions(String name, ActorMachine actorMachine) {
        int i = 0;
        for (Transition transition : actorMachine.getTransitions()) {
            emitter().emit("void %s::transition_%d() {", name, i);
            emitter().increaseIndentation();
            transition.getBody().forEach(code()::execute);
            emitter().decreaseIndentation();
            emitter().emit("}");
            emitter().emit("");
            emitter().emit("");
            i++;
        }
    }

    default void actorMachineTransitionsPrototype(ActorMachine actorMachine) {
        int i = 0;
        for (Transition transition : actorMachine.getTransitions()) {
            emitter().emit("void transition_%d();", i);
            emitter().emit("");
            i++;
        }
    }

    default void actorMachineConditions(String name, ActorMachine actorMachine) {
        int i = 0;
        for (Condition condition : actorMachine.getConditions()) {
            emitter().emit("bool %s::condition_%d() {", name, i);
            emitter().increaseIndentation();
            emitter().emit("return %s;", evaluateCondition(condition));
            emitter().decreaseIndentation();
            emitter().emit("}");
            emitter().emit("");
            emitter().emit("");
            i++;
        }
    }

    default void actorMachineConditionsPrototypes(ActorMachine actorMachine) {
        int i = 0;
        for (Condition condition : actorMachine.getConditions()) {
            emitter().emit("bool condition_%d();", i);
            emitter().emit("");
            i++;
        }
    }

    String evaluateCondition(Condition condition);

    default String evaluateCondition(PredicateCondition condition) {
        return code().evaluate(condition.getExpression());
    }

    default String evaluateCondition(PortCondition condition) {
        if (condition.isInputCondition()) {
            //return String.format("channel_has_data_%s(this->%s_channel, %d)", code().inputPortTypeSize(condition.getPortName()), condition.getPortName().getName(), condition.N());
            return String.format("%s_channel->has_data(%d)", condition.getPortName().getName(), condition.N());
        } else {
           // return String.format("channel_has_space_%s(this->%s_channels, %d)", code().outputPortTypeSize(condition.getPortName()), condition.getPortName().getName(), condition.N());
            return String.format("%s_channels->has_space(%d)", condition.getPortName().getName(), condition.N());
        }
    }


    default void actorMachineStateInit(String name, ActorMachine actorMachine) {
        int i = 0;
        for (Scope scope : actorMachine.getScopes()) {
            emitter().emit("void %s::init_scope_%d() {", name, i);
            emitter().increaseIndentation();
            for (VarDecl var : scope.getDeclarations()) {
                Type type = types().declaredType(var);
                if (var.isExternal() && type instanceof CallableType) {
                    String wrapperName = backend().callables().externalWrapperFunctionName(var);
                    String variableName = backend().variables().declarationName(var);
                    String t = backend().callables().mangle(type).encode();
                    emitter().emit("this->%s = (%s) { *%s, NULL };", variableName, t, wrapperName);
                } else if (var.getValue() != null) {
                    emitter().emit("{");
                    emitter().increaseIndentation();
                    code().copy(types().declaredType(var), "this->" + backend().variables().declarationName(var), types().type(var.getValue()), code().evaluate(var.getValue()));
                    emitter().decreaseIndentation();
                    emitter().emit("}");
                }
            }
            emitter().decreaseIndentation();
            emitter().emit("}");
            emitter().emit("");
            emitter().emit("");
            i++;
        }
    }

    default void actorMachineScopesInit(ActorMachine actorMachine) {
        int i = 0;
        for (Scope scope : actorMachine.getScopes()) {
            emitter().emit("void init_scope_%d();", i);

            emitter().emit("");
            i++;
        }
    }


    default void actorMachineState(String name, ActorMachine actorMachine) {
        emitter().emit("int program_counter;");
        emitter().emit("");

        if (!actorMachine.getValueParameters().isEmpty()) {
            emitter().emit("// -- parameters");
            for (VarDecl param : actorMachine.getValueParameters()) {
                String decl = code().declaration(types().declaredType(param), backend().variables().declarationName(param));
                emitter().emit("%s;", decl);
            }
            emitter().emit("");
        }

        if (!actorMachine.getInputPorts().isEmpty()) {
            emitter().emit("// -- input ports");
            for (PortDecl input : actorMachine.getInputPorts()) {
                String type = backend().channels().targetEndTypeSize(new Connection.End(Optional.of(backend().instance().get().getInstanceName()), input.getName()));
                emitter().emit("Fifo<%s > *%s_channel;", type, input.getName());
            }
            emitter().emit("");
        }

        if (!actorMachine.getOutputPorts().isEmpty()) {
            emitter().emit("// -- output ports");
            for (PortDecl output : actorMachine.getOutputPorts()) {
                Connection.End source = new Connection.End(Optional.of(backend().instance().get().getInstanceName()), output.getName());
                String type = backend().channels().sourceEndTypeSize(source);
                emitter().emit("FifoList<%s > *%s_channels;", type, output.getName());
            }
            emitter().emit("");
        }

        int i = 0;
        for (Scope scope : actorMachine.getScopes()) {
            emitter().emit("// -- scope %d", i);
            backend().callables().declareEnvironmentForCallablesInScope(scope);
            for (VarDecl var : scope.getDeclarations()) {
                String decl = code().declaration(types().declaredType(var), backend().variables().declarationName(var));
                emitter().emit("%s;", decl);
            }
            emitter().emit("");
            i++;
        }
    }

    default void actorDecls(List<GlobalEntityDecl> entityDecls) {
        entityDecls.forEach(backend().structure()::actorDecl);
    }
}
