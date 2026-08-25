package com.iridium126.createmanaindustry.client.particles.shaderpack;

import io.github.douira.glsl_transformer.GLSLParser;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTBuilder;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.parser.ParseShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Injects the CMI MODEL-particle vertex program (SSBO-driven instanced allay
 * rendering) into an Iris shaderpack block/terrain vertex shader, so the merged
 * program receives the pack's lighting, fog and tone-mapping semantics while
 * all geometry data keeps coming from the particle engine's GPU buffers.
 *
 * <p>Structure adapted from iris-veil-compat's {@code GlslTransformerVeilPatcher}
 * (MIT, (c) top.leonx), which itself follows the Flywheel-compat pattern. The
 * shared mechanism: the foreign computation is parsed as its own translation
 * unit, its declarations are spliced in front of the pack's, and its {@code
 * main} body is prepended to the pack's {@code main} -- afterwards every pack
 * reference to the vanilla fixed-function vertex pipeline is AST-rewritten to
 * consume the foreign results.</p>
 *
 * <p>CMI-specific rewrites (all particle state lives in high-numbered SSBO
 * bindings the pack never touches):</p>
 * <ul>
 *   <li>{@code gl_Vertex} -> the VIEW-SPACE vertex computed from the particle
 *       pool ({@code cmi_VertexView}); {@code gl_ModelViewMatrix} collapses to
 *       {@code mat4(1.0)} because the view transform is already baked in --
 *       both explicit {@code proj * mv * pos} chains and fog distance math
 *       stay correct;</li>
 *   <li>{@code ftransform()} -> {@code gl_ProjectionMatrix * cmi_VertexView};</li>
 *   <li>{@code gl_Color} -> {@code cmi_Tint} (emitter colour keyframes);</li>
 *   <li>{@code gl_MultiTexCoord0} -> {@code cmi_TexCoord0v} (baked atlas UV);</li>
 *   <li>{@code gl_MultiTexCoord1} -> {@code cmi_LightCoordv}, a full-bright
 *       constant (vanilla Allay renders at block light 15, see
 *       {@code AllayRenderer.getBlockLightLevel});</li>
 *   <li>{@code gl_Normal} -> {@code cmi_NormalLevel}: the level-space pose
 *       normal; the pack's {@code gl_NormalMatrix} (rotation-only, Iris-fed)
 *       turns it into the view-space normal it expects;</li>
 *   <li>Iris extension attributes ({@code mc_Entity} etc.) are removed and
 *       replaced with neutral constants exactly like the reference patcher --
 *       D1 option A: pack terrain effects consuming them see placeholders.</li>
 * </ul>
 */
public final class ParticleVertexInjector {

    private static final AutoHintedMatcher<Expression> FTRANSFORM_EXPR =
        new AutoHintedMatcher<>("ftransform()", ParseShape.EXPRESSION);

    private static final Set<String> IRIS_EXTENSION_ATTRIBUTES = Set.of(
        "at_tangent", "mc_Entity", "mc_midTexCoord", "at_midBlock"
    );

    private static final Map<String, String> DEFAULT_REPLACEMENTS = Map.of(
        "at_tangent",     "vec4(1.0, 0.0, 0.0, 1.0)",
        "mc_Entity",      "vec2(0.0)",
        "mc_midTexCoord", "vec4(0.0, 0.0, 0.0, 1.0)",
        "at_midBlock",    "vec4(0.0)"
    );

    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^.*#version\\s+(\\d+)(\\s+\\w+)?", Pattern.DOTALL);

    private final SingleASTTransformer<PatchParams> transformer;
    private final SingleASTTransformer<PatchParams> cmiTransformer;

    private static final ParseShape<GLSLParser.CompoundStatementContext, CompoundStatement> COMPOUND_STATEMENT_SHAPE =
        new ParseShape<>(
            GLSLParser.CompoundStatementContext.class,
            GLSLParser::compoundStatement,
            ASTBuilder::visitCompoundStatement);

    public ParticleVertexInjector() {
        transformer = new SingleASTTransformer<>() {
            {
                setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
            }

            @Override
            public TranslationUnit parseTranslationUnit(Root rootInstance, String input) {
                java.util.regex.Matcher matcher = VERSION_PATTERN.matcher(input);
                if (!matcher.find()) {
                    throw new IllegalArgumentException("No #version directive found in pack vertex source!");
                }
                int originalVersion = Integer.parseInt(matcher.group(1));
                // Floor at 430: the injected CMI block declares std430 SSBOs
                // (GLSL 430+). The primary target pack (Photon v1.3b) ships
                // gbuffers_block.vsh as #version 400 compatibility -- without
                // this floor the merged program only links where drivers accept
                // SSBOs below 4.30 (spec-illegal) and silently falls back to the
                // self-drawn path everywhere else.
                int finalVersion = Math.max(originalVersion, 430);
                input = matcher.replaceAll("#version " + finalVersion + " compatibility\n");
                transformer.getLexer().version = Version.fromNumber(finalVersion);
                return super.parseTranslationUnit(rootInstance, input);
            }
        };
        transformer.setTransformation(this::transform);

        cmiTransformer = new SingleASTTransformer<>();
        cmiTransformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        // match the injected source's feature level -- the merged CMI source
        // contains uint/uvec2 arithmetic and std430 buffer blocks (430 syntax)
        cmiTransformer.getLexer().version = Version.fromNumber(430);
    }

    /**
     * Patches the pack vertex source. Returns the patched source unchanged on
     * any transform failure (the caller logs and falls back to the self-drawn
     * path), mirroring the reference patcher's failure contract.
     */
    public String patch(String packVertexSource, String cmiVertexSource, String shaderName) {
        if (packVertexSource == null || cmiVertexSource == null || cmiVertexSource.isEmpty())
            return packVertexSource;
        try {
            return transformer.transform(packVertexSource,
                new PatchParams(cmiVertexSource, shaderName));
        } catch (Exception e) {
            ShaderPackProgramCompiler.logTransformFailure(shaderName, e);
            return packVertexSource;
        }
    }

    private void transform(TranslationUnit packTree, Root packRoot, PatchParams params) {
        // Step 1: parse our program as a separate tree
        TranslationUnit cmiTree = cmiTransformer.parseSeparateTranslationUnit(params.cmiVertexSource);
        var cmiRoot = cmiTree.getRoot();

        // Step 2: splice our declarations in front of the pack's (main excluded,
        // injected below; names colliding with the pack's are skipped defensively
        // even though everything CMI-side is cmi_-prefixed)
        var cmiMainBody = cmiTree.getOneMainDefinitionBody();
        if (cmiMainBody == null)
            throw new IllegalStateException("CMI merged vertex source has no main()");
        var mainFuncDecl = cmiMainBody.getAncestor(ExternalDeclaration.class);

        List<ExternalDeclaration> decls = new ArrayList<>();
        for (var child : cmiTree.getChildren()) {
            if (child == mainFuncDecl) continue;
            if (child instanceof DeclarationExternalDeclaration dex
                    && dex.getDeclaration() instanceof TypeAndInitDeclaration t) {
                boolean clash = t.getMembers().stream()
                    .anyMatch(m -> hasGlobalDeclaration(packRoot, m.getName().getName()));
                if (clash) continue;
            }
            decls.add(child);
        }
        if (!decls.isEmpty())
            packTree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS, decls);

        // Step 3: prepend our main body (fresh scope, re-parsed to avoid moving
        // nodes between roots -- glsl-transformer rejects cross-root moves)
        String blockSource = "{\n" + ASTPrinter.printSimple(cmiMainBody) + "\n}";
        CompoundStatement cmiBlock = cmiTransformer.parseNodeSeparate(
            cmiTransformer.getRootSupplier(), COMPOUND_STATEMENT_SHAPE, blockSource);
        packTree.prependMainFunctionBody(cmiBlock.getStatements());

        // Step 4: rewrite the pack's fixed-function vertex pipeline onto our
        // computed results (view space is baked; matrices collapse to identity)
        packRoot.replaceReferenceExpressions(transformer, "gl_Vertex", "vec4(cmi_VertexView.xyz, 1.0)");
        packRoot.replaceReferenceExpressions(transformer, "gl_ModelViewMatrix", "mat4(1.0)");
        packRoot.replaceExpressionMatches(transformer, FTRANSFORM_EXPR,
            "(gl_ProjectionMatrix * cmi_VertexView)");
        packRoot.replaceReferenceExpressions(transformer, "gl_Color", "cmi_Tint");
        packRoot.replaceReferenceExpressions(transformer, "gl_Normal", "cmi_NormalLevel");
        packRoot.replaceReferenceExpressions(transformer, "gl_MultiTexCoord0", "cmi_TexCoord0v");
        packRoot.replaceReferenceExpressions(transformer, "gl_MultiTexCoord1", "cmi_LightCoordv");

        // Step 5: neutralise the Iris extension attributes (D1 option A)
        var dims = new HashMap<String, Integer>();
        removeExtensionAttributes(packRoot, dims);
        replaceExtensionAttributes(packRoot, dims);
    }

    // NOTE: the transformer field must be passed as the parser argument to
    // Root.replaceReferenceExpressions -- a Root is not an ASTParser.

    // ---- extension attribute handling (verbatim strategy from the reference) ----

    private void removeExtensionAttributes(Root root, Map<String, Integer> dims) {
        root.process(
            root.nodeIndex.getStream(DeclarationExternalDeclaration.class).distinct(),
            node -> {
                if (node.getDeclaration() instanceof TypeAndInitDeclaration t) {
                    var found = t.getMembers().stream()
                        .filter(m -> IRIS_EXTENSION_ATTRIBUTES.contains(m.getName().getName()))
                        .findAny();
                    if (found.isPresent()) {
                        if (t.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier s) {
                            var d = s.type.getDimensions();
                            dims.put(found.get().getName().getName(), d.length > 0 ? d[0] : 1);
                        }
                        node.detachAndDelete();
                    }
                }
            }
        );
    }

    private void replaceExtensionAttributes(Root root, Map<String, Integer> dims) {
        for (var e : DEFAULT_REPLACEMENTS.entrySet()) {
            root.replaceReferenceExpressions(transformer, e.getKey(), extensionAttributeReplacement(e.getKey(), dims));
        }
    }

    private static String extensionAttributeReplacement(String name, Map<String, Integer> dims) {
        Integer dimension = dims.get(name);
        if (dimension == null) return DEFAULT_REPLACEMENTS.get(name);
        return switch (name) {
            case "at_tangent" -> switch (dimension) {
                case 2 -> "vec2(1.0, 0.0)";
                case 3 -> "vec3(1.0, 0.0, 0.0)";
                default -> DEFAULT_REPLACEMENTS.get(name);
            };
            case "mc_midTexCoord" -> dimension == 4
                ? "vec4(0.0, 0.0, 0.0, 1.0)"
                : vectorZero(dimension);
            case "mc_Entity", "at_midBlock" -> vectorZero(dimension);
            default -> DEFAULT_REPLACEMENTS.get(name);
        };
    }

    private static String vectorZero(int dimension) {
        return switch (dimension) {
            case 1 -> "0.0";
            case 2 -> "vec2(0.0)";
            case 3 -> "vec3(0.0)";
            default -> "vec4(0.0)";
        };
    }

    private static boolean hasGlobalDeclaration(Root root, String name) {
        return root.nodeIndex.getStream(DeclarationExternalDeclaration.class).distinct()
            .anyMatch(node -> {
                if (node.getDeclaration() instanceof TypeAndInitDeclaration t) {
                    return t.getMembers().stream().anyMatch(m -> name.equals(m.getName().getName()));
                }
                return false;
            });
    }

    public static final class PatchParams implements JobParameters {
        public final String cmiVertexSource;
        public final String shaderName;

        public PatchParams(String cmiVertexSource, String shaderName) {
            this.cmiVertexSource = cmiVertexSource;
            this.shaderName = shaderName;
        }
    }
}