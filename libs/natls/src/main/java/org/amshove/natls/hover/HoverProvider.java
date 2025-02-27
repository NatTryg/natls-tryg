package org.amshove.natls.hover;

import org.amshove.natls.markupcontent.IMarkupContentBuilder;
import org.amshove.natls.markupcontent.MarkupContentBuilderFactory;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.natural.*;
import org.amshove.natparse.natural.builtin.BuiltInFunctionTable;
import org.amshove.natparse.natural.builtin.SystemFunctionDefinition;
import org.amshove.natparse.natural.builtin.SystemVariableDefinition;
import org.eclipse.lsp4j.Hover;

import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HoverProvider
{
	public static final Hover EMPTY_HOVER = null; // This should be null according to the LSP spec

	public Hover createHover(HoverContext context)
	{
		// This should always come first, as this is essentially hovering a leaf node which is a variable.
		// If this comes after other possible hovers then hovering e.g. a CALLNAT parameter always returns
		// the module documentation instead of the passed parameter.
		if (context.nodeToHover()instanceof ISymbolReferenceNode symbolReferenceNode && symbolReferenceNode.reference()instanceof IVariableNode variableNode)
		{
			return hoverVariable(variableNode, context);
		}

		if (context.nodeToHover() instanceof IDefineWorkFileNode
			|| context.nodeToHover().parent() instanceof IDefineWorkFileNode)
		{
			if (context.tokenToHover().kind() == SyntaxKind.ATTRIBUTES)
			{
				return StaticHovers.WORKFILE_ATTRIBUTE_HOVER;
			}

			if (context.tokenToHover().kind() == SyntaxKind.TYPE)
			{
				return StaticHovers.WORKFILE_TYPE_HOVER;
			}
		}

		if (context.tokenToHover().kind() == SyntaxKind.MASK)
		{
			return StaticHovers.MASK_HOVER;
		}

		// TODO: This should use nodes instead of tokens, but does not work currently in every case, as they're only created when parsing operands
		//		and some node types that use operands aren't implemented yet.
		//		The `tokenToHover` can then be removed from the context.
		if (context.tokenToHover().kind().isSystemVariable() || context.tokenToHover().kind().isSystemFunction())
		{
			return hoverBuiltinFunction(context.tokenToHover().kind());
		}

		if (context.nodeToHover()instanceof IMathFunctionOperandNode mathFunction)
		{
			return MathFunctionHoverRegistry.getHover(mathFunction);
		}

		if (context.nodeToHover().parent()instanceof IMathFunctionOperandNode mathFunction)
		{
			return MathFunctionHoverRegistry.getHover(mathFunction);
		}

		if (context.nodeToHover()instanceof IModuleReferencingNode moduleReferencingNode)
		{
			return hoverExternalModule(moduleReferencingNode);
		}

		if (context.nodeToHover().parent()instanceof IModuleReferencingNode moduleReferencingNode)
		{
			return hoverExternalModule(moduleReferencingNode);
		}

		if (context.nodeToHover()instanceof IVariableNode variableNode)
		{
			return hoverVariable(variableNode, context);
		}

		if (context.nodeToHover().parent()instanceof IVariableNode variableNode)
		{
			return hoverVariable(variableNode, context);
		}

		return EMPTY_HOVER;
	}

	public Hover hoverModule(INaturalModule module)
	{
		if (module == null)
		{
			return EMPTY_HOVER;
		}

		var contentBuilder = MarkupContentBuilderFactory.newBuilder();
		contentBuilder.appendStrong("%s.%s".formatted(module.file().getLibrary().getName(), module.file().getReferableName())).appendNewline();

		if (!module.file().getFilenameWithoutExtension().equals(module.file().getReferableName()))
		{
			contentBuilder.appendItalic("File: %s".formatted(module.file().getFilenameWithoutExtension())).appendNewline();
		}

		var documentation = module.moduleDocumentation();
		if (documentation != null && !documentation.trim().isEmpty())
		{
			contentBuilder.appendCode(documentation);
		}

		if (module instanceof IFunction function && function.returnType() != null)
		{
			contentBuilder.appendSection(
				"Result", cb -> cb
					.appendCode("RETURNS " + Objects.requireNonNull(function.returnType()).toShortString()).appendNewline()
			);
		}

		addModuleParameter(contentBuilder, module);

		return new Hover(contentBuilder.build());
	}

	private Hover hoverExternalModule(IModuleReferencingNode moduleReferencingNode)
	{
		var module = moduleReferencingNode.reference();
		return hoverModule(module);
	}

	private Hover hoverBuiltinFunction(SyntaxKind kind)
	{
		var builtinFunction = BuiltInFunctionTable.getDefinition(kind);
		var contentBuilder = MarkupContentBuilderFactory.newBuilder();

		var signature = builtinFunction.name();
		if (builtinFunction instanceof SystemFunctionDefinition function)
		{
			signature += "(";
			signature += function.parameter().stream()
				.map(p ->
				{
					var parameter = p.name();
					if (p.type().format() != DataFormat.NONE)
					{
						parameter += p.type().toShortString();
					}
					return p.mandatory() ? parameter : "[%s]".formatted(parameter);
				})
				.collect(Collectors.joining(", "));
			signature += ")";
		}

		signature += " : %s".formatted(builtinFunction.type().toShortString());
		contentBuilder.appendCode(signature);
		contentBuilder.appendParagraph("---");

		if (builtinFunction instanceof SystemVariableDefinition variableDefinition)
		{
			contentBuilder.appendStrong(variableDefinition.isModifiable() ? "modifiable" : "unmodifiable").appendNewline();
		}

		contentBuilder.appendParagraph(builtinFunction.documentation());
		return new Hover(contentBuilder.build());
	}

	private Hover hoverVariable(IVariableNode variable, HoverContext context)
	{
		var contentBuilder = MarkupContentBuilderFactory.newBuilder();
		var declaration = formatVariableDeclaration(context.file().module(), variable);
		contentBuilder.appendCode(declaration.declaration);

		var pathToVariableHover = VariableContextHover.create(context, variable);
		pathToVariableHover.addVariableContext(contentBuilder);

		return new Hover(contentBuilder.build());
	}

	private VariableDeclarationFormat formatVariableDeclaration(INaturalModule module, IVariableNode variable)
	{
		var declaration = "%s %d %s".formatted(variable.scope().toString(), variable.level(), variable.name());
		if (variable instanceof ITypedVariableNode typedVariableNode)
		{
			declaration += " %s".formatted(typedVariableNode.formatTypeForDisplay());
			if (typedVariableNode.type().initialValue() != null)
			{
				var initValue = typedVariableNode.type().initialValue()instanceof ITokenNode tokenNode
					? tokenNode.token().source()
					: ((IStringConcatOperandNode) typedVariableNode.type().initialValue()).stringValue();
				declaration += " %s<%s>".formatted(
					typedVariableNode.type().isConstant() ? "CONST" : "INIT",
					initValue
				);
			}
		}

		if (variable.findDescendantToken(SyntaxKind.VALUE) != null)
		{
			declaration += " BY VALUE";
			if (variable.findDescendantToken(SyntaxKind.RESULT) != null)
			{
				declaration += " RESULT";
			}
		}

		if (variable.findDescendantToken(SyntaxKind.OPTIONAL) != null)
		{
			declaration += " OPTIONAL";
		}

		var comment = module.extractLineComment(variable.position().line());
		return new VariableDeclarationFormat(declaration, comment);
	}

	private void addModuleParameter(IMarkupContentBuilder contentBuilder, INaturalModule module)
	{
		if (!(module instanceof IHasDefineData hasDefineData) || hasDefineData.defineData() == null)
		{
			return;
		}

		Function<IUsingNode, String> usingFormatter = using -> "PARAMETER USING %s %s".formatted(using.target().source(), module.extractLineComment(using.position().line()));
		Function<IVariableNode, String> variableFormatter = variable ->
		{
			var declaration = formatVariableDeclaration(module, variable);
			return "%s%s".formatted(
				declaration.declaration,
				!declaration.comment.isEmpty()
					? " %s".formatted(declaration.comment)
					: ""
			);
		};

		contentBuilder.appendSection("Parameter", nested ->
		{
			var parameterBlock = new StringBuilder();
			for (var parameterDefinition : hasDefineData.defineData().declaredParameterInOrder())
			{
				if (parameterDefinition instanceof IUsingNode using)
				{
					parameterBlock.append(usingFormatter.apply(using));
				}
				else
					if (parameterDefinition instanceof IVariableNode variable)
					{
						parameterBlock.append(variableFormatter.apply(variable));
					}

				parameterBlock.append(System.lineSeparator());
			}

			nested.appendCode(parameterBlock.toString().stripIndent().trim());
		});
	}

	private record VariableDeclarationFormat(String declaration, String comment)
	{}
}
