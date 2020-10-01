package org.lombokit;

import com.google.auto.service.AutoService;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static com.sun.tools.javac.code.Flags.*;
import static javax.tools.Diagnostic.Kind.NOTE;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("org.lombokit.ADT")
@AutoService(Processor.class)
public class ADTProcessor extends AbstractProcessor {
    private static final String VISITOR_INTERFACE = "Visitor";
    private static final String DEFAULT_VISITOR_CLASS = "DefaultVisitor";
    private static final String CASE_PARAM_NAME = "x";
    private static final String CASE_PREFIX = "case";
    private static final String OTHERWISE_FUNCTION = "otherwise";
    private static final String MATCH_FUNCTION = "match";
    private static final String MATCH_PARAM_NAME = "v";

    /**
     * 抽象语法树
     */
    private JavacTrees javacTrees;

    /**
     * AST
     */
    private TreeMaker treeMaker;

    /**
     * 标识符
     */
    private Names names;

    /**
     * 日志处理
     */
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.javacTrees = JavacTrees.instance(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        messager.printMessage(NOTE, "Start processing ADT annotations");
        Set<? extends Element> elementsAnnotatedWith = roundEnv.getElementsAnnotatedWith(ADT.class);
        elementsAnnotatedWith.forEach(e -> {
            messager.printMessage(NOTE, "Processing ADT annotation for " + e.toString());
            JCTree tree = javacTrees.getTree(e);
            tree.accept(new TreeTranslator() {
                @Override
                public void visitClassDef(JCTree.JCClassDecl adtClassDef) {
                    // ADT 父类必须是 abstract class
                    if ((adtClassDef.mods.flags & ABSTRACT) == 0) {
                        return;
                    }
                    if ((adtClassDef.mods.flags & INTERFACE) != 0) {
                        return;
                    }
                    if (adtClassDef.extending != null) {
                        return;
                    }

                    List<JCTree.JCClassDecl> adtSubclassDeclList = List.nil();
                    for (JCTree jcTree : adtClassDef.defs) {
                        if (!jcTree.getKind().equals(Tree.Kind.CLASS)) {
                            continue;
                        }
                        JCTree.JCClassDecl adtSubclassDecl = (JCTree.JCClassDecl) jcTree;
                        // ADT子类必须为非abstract
                        if ((adtSubclassDecl.mods.flags & ABSTRACT) != 0) {
                            continue;
                        }
                        if (!(adtSubclassDecl.extending instanceof JCTree.JCIdent)) {
                            continue;
                        }
                        JCTree.JCIdent extending = (JCTree.JCIdent) adtSubclassDecl.extending;
                        if (!extending.sym.equals(adtClassDef.sym)) {
                            continue;
                        }
                        messager.printMessage(NOTE, "Found subclass " + adtSubclassDecl.sym + " of " + adtClassDef.sym);
                        adtSubclassDeclList = adtSubclassDeclList.append(adtSubclassDecl);
                    }

                    messager.printMessage(NOTE, "Total count of subclasses of " + adtClassDef.sym + ":" + adtSubclassDeclList.size());

                    JCTree.JCClassDecl visitorDecl = makeVisitorDecl(adtSubclassDeclList);
                    adtClassDef.defs = adtClassDef.defs.append(visitorDecl);

                    JCTree.JCClassDecl defaultVisitorDecl = makeDefaultVisitorDecl(adtClassDef, visitorDecl);
                    adtClassDef.defs = adtClassDef.defs.append(defaultVisitorDecl);

                    JCTree.JCMethodDecl matchMethodDecl = makeMatchMethodDecl(visitorDecl);
                    adtClassDef.defs = adtClassDef.defs.append(matchMethodDecl);

                    for (JCTree.JCClassDecl adtSubclassDecl : adtSubclassDeclList) {
                        JCTree.JCMethodDecl matchMethodOverrideDecl = makeMatchMethodOverrideDecl(adtSubclassDecl, visitorDecl);
                        adtSubclassDecl.defs = adtSubclassDecl.defs.append(matchMethodOverrideDecl);
                    }
                }
            });
        });
        return false;
    }

    private JCTree.JCClassDecl makeVisitorDecl(List<JCTree.JCClassDecl> adtSubclassDeclList) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC | INTERFACE);
        Name name = names.fromString(VISITOR_INTERFACE);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        JCTree.JCExpression extending = null;
        List<JCTree.JCExpression> implementing = List.nil();
        List<JCTree> defs = List.nil();

        typarams = typarams.append(treeMaker.TypeParameter(names.T, List.nil()));

        for (JCTree.JCClassDecl adtSubclassDecl : adtSubclassDeclList) {
            defs = defs.append(makeCaseMethodDecl(adtSubclassDecl));
        }

        return treeMaker.ClassDef(mods, name, typarams, extending, implementing, defs);
    }

    private JCTree.JCMethodDecl makeCaseMethodDecl(JCTree.JCClassDecl adtSubclassDecl) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC);
        Name name = names.fromString(CASE_PREFIX + adtSubclassDecl.name);
        JCTree.JCIdent restype = treeMaker.Ident(names.T);

        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCVariableDecl> params = List.nil();
        List<JCTree.JCExpression> thrown = List.nil();
        JCTree.JCBlock body = null;
        JCTree.JCExpression defaultValue = null;

        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(PARAMETER),
                names.fromString(CASE_PARAM_NAME),
                treeMaker.Ident(adtSubclassDecl.name),
                null
        );
        params = params.append(param);

        return treeMaker.MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue);
    }

    private JCTree.JCClassDecl makeDefaultVisitorDecl(JCTree.JCClassDecl adtClassDecl, JCTree.JCClassDecl visitorDecl) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC | STATIC | ABSTRACT);
        Name name = names.fromString(DEFAULT_VISITOR_CLASS);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        JCTree.JCExpression extending = null;
        List<JCTree.JCExpression> implementing = List.nil();
        List<JCTree> defs = List.nil();

        typarams = typarams.append(treeMaker.TypeParameter(names.T, List.nil()));
        implementing = implementing.append(treeMaker.TypeApply(treeMaker.Ident(visitorDecl.name), List.<JCTree.JCExpression>nil().append(treeMaker.Ident(names.T))));

        defs = defs.append(makeOtherwiseMethodDecl(adtClassDecl));
        for (JCTree def : visitorDecl.defs) {
            if (!(def instanceof JCTree.JCMethodDecl)) {
                continue;
            }
            if (!((JCTree.JCMethodDecl) def).name.toString().startsWith("case")) {
                continue;
            }
            defs = defs.append(makeCaseMethodOverrideDecl((JCTree.JCMethodDecl) def));
        }

        return treeMaker.ClassDef(mods, name, typarams, extending, implementing, defs);
    }


    private JCTree.JCMethodDecl makeCaseMethodOverrideDecl(JCTree.JCMethodDecl caseMethodDecl) {
        JCTree.JCModifiers mods = caseMethodDecl.mods;
        Name name = caseMethodDecl.name;
        JCTree.JCIdent restype = treeMaker.Ident(names.T);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCVariableDecl> params = caseMethodDecl.params;
        List<JCTree.JCExpression> thrown = caseMethodDecl.thrown;
        JCTree.JCBlock body = null;

        body = treeMaker.Block(0, List.<JCTree.JCStatement>nil().append(
                treeMaker.Return(
                        treeMaker.Apply(
                                List.nil(),
                                treeMaker.Ident(names.fromString(OTHERWISE_FUNCTION)),
                                List.<JCTree.JCExpression>nil().append(treeMaker.Ident(params.get(0).name))))));
        JCTree.JCExpression defaultValue = null;

        return treeMaker.MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue);
    }

    private JCTree makeOtherwiseMethodDecl(JCTree.JCClassDecl adtClassDecl) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC|ABSTRACT);
        Name name = names.fromString(OTHERWISE_FUNCTION);
        JCTree.JCIdent restype = treeMaker.Ident(names.T);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCVariableDecl> params = List.nil();
        List<JCTree.JCExpression> thrown = List.nil();
        JCTree.JCBlock body = null;
        JCTree.JCExpression defaultValue = null;

        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(PARAMETER),
                names.fromString(CASE_PARAM_NAME),
                treeMaker.Ident(adtClassDecl.name),
                null
        );
        params = params.append(param);

        return treeMaker.MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue);
    }

    private JCTree.JCMethodDecl makeMatchMethodDecl(JCTree.JCClassDecl visitorDecl) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC|ABSTRACT);
        Name name = names.fromString(MATCH_FUNCTION);
        JCTree.JCIdent restype = treeMaker.Ident(names.T);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCVariableDecl> params = List.nil();
        List<JCTree.JCExpression> thrown = List.nil();
        JCTree.JCBlock body = null;
        JCTree.JCExpression defaultValue = null;

        typarams = typarams.append(treeMaker.TypeParameter(names.T, List.nil()));

        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(PARAMETER),
                names.fromString(MATCH_PARAM_NAME),
                treeMaker.TypeApply(treeMaker.Ident(visitorDecl.name), List.<JCTree.JCExpression>nil().append(treeMaker.Ident(names.T))),
                null
        );
        params = params.append(param);

        return treeMaker.MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue);
    }


    private JCTree.JCMethodDecl makeMatchMethodOverrideDecl(JCTree.JCClassDecl subclassDecl, JCTree.JCClassDecl visitorDecl) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(PUBLIC);
        Name name = names.fromString(MATCH_FUNCTION);
        JCTree.JCIdent restype = treeMaker.Ident(names.T);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCVariableDecl> params = List.nil();
        List<JCTree.JCExpression> thrown = List.nil();
        JCTree.JCBlock body = null;
        JCTree.JCExpression defaultValue = null;

        typarams = typarams.append(treeMaker.TypeParameter(names.T, List.nil()));

        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(PARAMETER),
                names.fromString(MATCH_PARAM_NAME),
                treeMaker.TypeApply(treeMaker.Ident(visitorDecl.name), List.<JCTree.JCExpression>nil().append(treeMaker.Ident(names.T))),
                null
        );
        params = params.append(param);

        body = treeMaker.Block(0, List.<JCTree.JCStatement>nil().append(
                treeMaker.Return(
                        treeMaker.Apply(
                                List.nil(),
                                treeMaker.Select(
                                        treeMaker.Ident(param.name),
                                        names.fromString(CASE_PREFIX + subclassDecl.name.toString())
                                ),
                                List.<JCTree.JCExpression>nil().append(treeMaker.Ident(names.fromString("this")))))));
        return treeMaker.MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue);
    }

}
