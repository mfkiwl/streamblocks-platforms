package ch.epfl.vlsc.hls.backend;

import ch.epfl.vlsc.platformutils.Emitter;
import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.attribute.Types;
import se.lth.cs.tycho.ir.IRNode;
import se.lth.cs.tycho.ir.Variable;
import se.lth.cs.tycho.ir.decl.GeneratorVarDecl;
import se.lth.cs.tycho.ir.decl.VarDecl;
import se.lth.cs.tycho.ir.expr.*;
import se.lth.cs.tycho.ir.stmt.StmtCall;
import se.lth.cs.tycho.ir.util.ImmutableList;
import se.lth.cs.tycho.type.ListType;
import se.lth.cs.tycho.type.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Module
public interface ExpressionEvaluator {

    @Binding(BindingKind.INJECTED)
    VivadoHLSBackend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default Variables variables() {
        return backend().variables();
    }

    default Declarations declarations() {
        return backend().declarations();
    }

    default Types types() {
        return backend().types();
    }

    default ChannelsUtils channelsutils() {
        return backend().channelsutils();
    }

    default TypesEvaluator typeseval() {
        return backend().typeseval();
    }

    // -- Evaluate Expressions

    default String evaluateCall(Expression expression) {
        return evaluate(expression);
    }

    default String evaluateCall(ExprVariable variable) {
        IRNode parent = backend().tree().parent(variable);

        if (parent instanceof StmtCall || parent instanceof ExprApplication) {
            return variable.getVariable().getName();
        }

        return variables().name(variable.getVariable());
    }

    String evaluate(Expression expr);


    /**
     * Evaluate expression variable
     *
     * @param variable
     * @return
     */
    default String evaluate(ExprVariable variable) {
        IRNode parent = backend().tree().parent(variable);
        return variables().name(variable.getVariable());
    }

    /**
     * Evaluate a reference expression
     *
     * @param ref
     * @return
     */
    default String evaluate(ExprRef ref) {
        VarDecl decl = backend().varDecls().declaration(ref.getVariable());
        Type type = backend().types().declaredType(decl);
        if (type instanceof ListType) {
            return variables().name(ref.getVariable());
        } else {
            return "(&" + variables().name(ref.getVariable()) + ")";
        }
    }

    /**
     * Evaluate a dereference expression
     *
     * @param deref
     * @return
     */
    default String evaluate(ExprDeref deref) {
        Expression expr = deref.getReference();
        if (expr instanceof ExprVariable) {
            Variable var = ((ExprVariable) expr).getVariable();
            VarDecl decl = backend().varDecls().declaration(var);
            Type type = backend().types().declaredType(decl);
            if (type instanceof ListType) {
                return evaluate(deref.getReference());
            }
        }

        return "(*" + evaluate(deref.getReference()) + ")";
    }

    /**
     * Evaluate an expression Globals variable
     *
     * @param variable
     * @return
     */
    default String evaluate(ExprGlobalVariable variable) {
        return variables().globalName(variable);
    }

    /**
     * Evaluate expression literal
     *
     * @param literal
     * @return
     */

    default String evaluate(ExprLiteral literal) {
        switch (literal.getKind()) {
            case Integer:
                return literal.getText();
            case True:
                return "true";
            case False:
                return "false";
            case Real:
                return literal.getText();
            case String:
                return literal.getText();
            default:
                throw new UnsupportedOperationException(literal.getText());
        }
    }

    /**
     * Evaluate expression input
     *
     * @param input
     * @return
     */
    default String evaluate(ExprInput input) {
        String tmp = variables().generateTemp();
        emitter().emit("%s;", declarations().declarationTemp(types().type(input), tmp));

        if (input.hasRepeat()) {
            if (input.getOffset() == 0) {
                emitter().emit("pinPeekFront(%s, %s[0]);", channelsutils().definedInputPort(input.getPort()), tmp);
            } else {
                throw new RuntimeException("not implemented");
            }
        } else {
            if (input.getOffset() == 0) {
                emitter().emit("pinPeekFront(%s, %s);", channelsutils().definedInputPort(input.getPort()), tmp);
            } else {
                emitter().emit("pinPeekFront(%s, %d, %s);", channelsutils().definedInputPort(input.getPort()), input.getOffset(), tmp);
            }
        }

        return tmp;
    }

    void evaluateWithLvalue(String lvalue, Expression expr);

    default void evaluateWithLvalue(String lvalue, ExprInput input) {
        if (input.hasRepeat()) {
            if (input.getOffset() == 0) {
                emitter().emit("pinPeekFront(%s, %s[0]);", channelsutils().definedInputPort(input.getPort()), lvalue);
            } else {
                throw new RuntimeException("not implemented");
            }
        } else {
            if (input.getOffset() == 0) {
                emitter().emit("pinPeekFront(%s, %s);", channelsutils().definedInputPort(input.getPort()), lvalue);
            } else {
                emitter().emit("pinPeekFront(%s, %d, %s);", channelsutils().definedInputPort(input.getPort()), input.getOffset(), lvalue);
            }
        }
    }


    /**
     * Evaluate binary expression
     *
     * @param binaryOp
     * @return
     */

    default String evaluate(ExprBinaryOp binaryOp) {
        assert binaryOp.getOperations().size() == 1 && binaryOp.getOperands().size() == 2;
        String operation = binaryOp.getOperations().get(0);
        Expression left = binaryOp.getOperands().get(0);
        Expression right = binaryOp.getOperands().get(1);
        switch (operation) {
            case "+":
            case "-":
            case "*":
            case "/":
            case "<":
            case "<=":
            case ">":
            case ">=":
            case "==":
            case "!=":
            case "<<":
            case ">>":
            case "&":
            case "|":
            case "^":
                return String.format("(%s %s %s)", evaluate(left), operation, evaluate(right));
            case "=":
                return String.format("%s == %s", evaluate(left), evaluate(right));
            case "mod":
                return String.format("(%s %% %s)", evaluate(left), evaluate(right));
            case "and":
            case "&&":
                String andResult = variables().generateTemp();
                emitter().emit("bool %s;", andResult);
                emitter().emit("if (%s) {", evaluate(left));
                emitter().increaseIndentation();
                emitter().emit("%s = %s;", andResult, evaluate(right));
                emitter().decreaseIndentation();
                emitter().emit("} else {");
                emitter().increaseIndentation();
                emitter().emit("%s = false;", andResult);
                emitter().decreaseIndentation();
                emitter().emit("}");
                return andResult;
            case "||":
            case "or":
                String orResult = variables().generateTemp();
                emitter().emit("bool %s;", orResult);
                emitter().emit("if (%s) {", evaluate(left));
                emitter().increaseIndentation();
                emitter().emit("%s = true;", orResult);
                emitter().decreaseIndentation();
                emitter().emit("} else {");
                emitter().increaseIndentation();
                emitter().emit("%s = %s;", orResult, evaluate(right));
                emitter().decreaseIndentation();
                emitter().emit("}");
                return orResult;
            default:
                throw new UnsupportedOperationException(operation);
        }
    }

    /**
     * Evaluate unary expression
     *
     * @param unaryOp
     * @return
     */
    default String evaluate(ExprUnaryOp unaryOp) {
        switch (unaryOp.getOperation()) {
            case "-":
            case "~":
                return String.format("%s(%s)", unaryOp.getOperation(), evaluate(unaryOp.getOperand()));
            case "not":
                return String.format("!%s", evaluate(unaryOp.getOperand()));
            default:
                throw new UnsupportedOperationException(unaryOp.getOperation());
        }
    }

    /**
     * Evaluate comprehensoin expression
     *
     * @param comprehension
     * @return
     */
    default String evaluate(ExprComprehension comprehension) {
        return evaluateComprehension(comprehension, types().type(comprehension));
    }

    String evaluateComprehension(ExprComprehension comprehension, Type t);

    default String evaluateComprehension(ExprComprehension comprehension, ListType t) {

        IRNode parent = backend().tree().parent(comprehension);
        String name;
        //if (parent instanceof LocalVarDecl) {
        //    LocalVarDecl v = (LocalVarDecl) parent;
        //    name = backend().variables().name(v);
        //} else {
        name = variables().generateTemp();
        String decl = declarations().declarationTemp(t, name);
        emitter().emit("%s;", decl);
        //}

        String index = variables().generateTemp();
        emitter().emit("size_t %s = 0;", index);
        evaluateListComprehension(comprehension, name, index);
        return name;
    }

    void evaluateListComprehension(Expression comprehension, String result, String index);

    default void evaluateListComprehension(ExprComprehension comprehension, String result, String index) {
        if (!comprehension.getFilters().isEmpty()) {
            throw new UnsupportedOperationException("Filters in comprehensions not supported.");
        }
        withGenerator(comprehension.getGenerator().getCollection(), comprehension.getGenerator().getVarDecls(), () -> {
            evaluateListComprehension(comprehension.getCollection(), result, index);
        });
    }

    default void evaluateListComprehension(ExprList list, String result, String index) {
        list.getElements().forEach(element ->
                emitter().emit("%s[%s++] = %s;", result, index, evaluate(element))
        );
    }

    void withGenerator(Expression collection, ImmutableList<GeneratorVarDecl> varDecls, Runnable body);

    default void withGenerator(ExprBinaryOp binOp, ImmutableList<GeneratorVarDecl> varDecls, Runnable action) {
        if (binOp.getOperations().equals(Collections.singletonList(".."))) {
            String from = evaluate(binOp.getOperands().get(0));
            String to = evaluate(binOp.getOperands().get(1));
            for (VarDecl d : varDecls) {
                Type type = types().declaredType(d);
                String name = variables().declarationName(d);
                emitter().emit("%s = %s;", declarations().declaration(type, name), from);
                emitter().emit("while (%s <= %s) {", name, to);
                emitter().increaseIndentation();
            }
            action.run();
            List<VarDecl> reversed = new ArrayList<>(varDecls);
            Collections.reverse(reversed);
            for (VarDecl d : reversed) {
                emitter().emit("%s++;", variables().declarationName(d));
                emitter().decreaseIndentation();
                emitter().emit("}");
            }
        } else {
            throw new UnsupportedOperationException(binOp.getOperations().get(0));
        }
    }

    /**
     * Evaluate list expression
     *
     * @param list
     * @return
     */
    default String evaluate(ExprList list) {
        ListType t = (ListType) types().type(list);
        if (t.getSize().isPresent()) {

            String name = variables().generateTemp();
            String decl = declarations().declarationTemp(t, name);
            String value = evaluateExprList(list);

            String init = "{" + value + " }";
            emitter().emit("%s = %s;", decl, init);
            return name;
        } else {
            return "NULL /* TODO: implement dynamically sized lists */";
        }
    }

    default String evaluateExprList(Expression expr) {
        return evaluate(expr);
    }

    default String evaluateExprList(ExprList list) {
        String value = list.getElements().stream().sequential()
                .map(this::evaluateExprList)
                .collect(Collectors.joining(", "));
        return value;
    }


    /**
     * Evaluate an indexer expression
     *
     * @param indexer
     * @return
     */
    default String evaluate(ExprIndexer indexer) {
        Variable var = evalExprIndexVar(indexer);
        return String.format("%s[%s]", variables().name(var), evalExprIndex(indexer, 0));
    }


    Variable evalExprIndexVar(Expression expr);


    default Variable evalExprIndexVar(ExprVariable expr) {
        return expr.getVariable();
    }

    default Variable evalExprIndexVar(ExprDeref expr) {
        if (expr.getReference() instanceof ExprVariable) {
            return ((ExprVariable) expr.getReference()).getVariable();
        }

        throw new UnsupportedOperationException();
    }

    default Variable evalExprIndexVar(ExprIndexer expr) {
        return evalExprIndexVar(expr.getStructure());
    }


    default String evalExprIndex(Expression expr, int index) {
        return evaluate(expr);
    }

    default String evalExprIndex(ExprIndexer expr, int index) {


        if (expr.getIndex() instanceof ExprIndexer) {
            ExprIndexer ii = (ExprIndexer) expr.getIndex();
            return String.format("%s[%s]", evalExprIndex(ii.getStructure(), index), evalExprIndex(ii.getIndex(), index));
        } else {
            if (expr.getStructure() instanceof ExprIndexer) {
                Variable var = evalExprIndexVar(expr);
                VarDecl varDecl = backend().varDecls().declaration(var);
                Type type = backend().types().declaredType(varDecl);
                List<Integer> sizeByDimension = backend().typeseval().sizeByDimension((ListType) type);
                index++;
                int factor = sizeByDimension.get(index);
                String i = evalExprIndex(expr.getIndex(), index);
                String s = evalExprIndex(expr.getStructure(), index);
                return String.format("(%s + (%s * %d))", i, s, factor);
            } else {
                return evalExprIndex(expr.getIndex(), index);
            }
        }

    }


    /**
     * Evaluate expression if
     *
     * @param expr
     * @return
     */
    default String evaluate(ExprIf expr) {
        Type type = types().type(expr);
        String temp = variables().generateTemp();
        String decl = declarations().declarationTemp(type, temp);
        emitter().emit("%s;", decl);
        emitter().emit("if (%s) {", evaluate(expr.getCondition()));
        emitter().increaseIndentation();
        Type thenType = types().type(expr.getThenExpr());
        String thenValue = evaluate(expr.getThenExpr());
        backend().statements().copy(type, temp, thenType, thenValue);
        emitter().decreaseIndentation();
        emitter().emit("} else {");
        emitter().increaseIndentation();
        Type elseType = types().type(expr.getElseExpr());
        String elseValue = evaluate(expr.getElseExpr());
        backend().statements().copy(type, temp, elseType, elseValue);
        emitter().decreaseIndentation();
        emitter().emit("}");
        return temp;
    }


    /**
     * Evaluate application expression
     *
     * @param apply
     * @return
     */
    default String evaluate(ExprApplication apply) {
        String fn;
        List<String> parameters = new ArrayList<>();

        for (Expression parameter : apply.getArgs()) {
            parameters.add(evaluate(parameter));
        }


        fn = evaluateCall(apply.getFunction());

        String result = variables().generateTemp();
        String decl = declarations().declarationTemp(types().type(apply), result);
        emitter().emit("%s = %s(%s);", decl, fn, String.join(", ", parameters));
        return result;
    }

    /**
     * Evaluate expression lambda
     *
     * @param lambda
     * @return
     */
    default String evaluate(ExprLambda lambda) {
        return "";
    }

    /**
     * Evaluate expression proc
     *
     * @param proc
     * @return
     */
    default String evaluate(ExprProc proc) {
        return "";
    }

    /**
     * Evaluate expression let
     *
     * @param let
     * @return
     */
    default String evaluate(ExprLet let) {
        for (VarDecl decl : let.getVarDecls()) {
            Type type = types().declaredType(decl);
            String name = variables().declarationName(decl);
            emitter().emit("%s;", declarations().declaration(type, name));
            backend().statements().copy(type, name, types().type(decl.getValue()), evaluate(decl.getValue()));
        }
        return evaluate(let.getBody());
    }


}