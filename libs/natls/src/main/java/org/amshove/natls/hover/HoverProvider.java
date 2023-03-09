package org.amshove.natls.hover;

import org.amshove.natls.markupcontent.IMarkupContentBuilder;
import org.amshove.natls.markupcontent.MarkupContentBuilderFactory;
import org.amshove.natparse.IPosition;
import org.amshove.natparse.NodeUtil;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.natural.*;
import org.amshove.natparse.natural.builtin.BuiltInFunctionTable;
import org.amshove.natparse.natural.builtin.SystemFunctionDefinition;
import org.amshove.natparse.natural.builtin.SystemVariableDefinition;
import org.eclipse.lsp4j.Hover;

import java.util.function.Function;
import java.util.stream.Collectors;

public class HoverProvider
{
	private static final Hover EMPTY_HOVER = null; // This should be null according to the LSP spec

	public Hover createHover(HoverContext context)
	{
		if (context.nodeToHover() instanceof IDefineWorkFileNode
			|| context.nodeToHover().parent() instanceof IDefineWorkFileNode)
		{
			if (context.tokenToHover().kind() == SyntaxKind.ATTRIBUTES)
			{
				return hoverWorkfileAttributes();
			}

			if (context.tokenToHover().kind() == SyntaxKind.TYPE)
			{
				return hoverWorkfileType();
			}
		}

		if (context.nodeToHover() == null)
		{
			return EMPTY_HOVER;
		}

		// TODO: This should use nodes instead of tokens, but does not work currently in every case, as they're only created when parsing operands
		//		and some node types that use operands aren't implemented yet.
		//		The `tokenToHover` can then be removed from the context.
		if (context.tokenToHover().kind().isSystemVariable() || context.tokenToHover().kind().isSystemFunction())
		{
			return hoverBuiltinFunction(context.tokenToHover().kind());
		}

		if (context.nodeToHover()instanceof IModuleReferencingNode moduleReferencingNode)
		{
			return hoverExternalModule(moduleReferencingNode);
		}

		if (context.nodeToHover()instanceof IVariableNode variableNode)
		{
			return hoverVariable(variableNode, context);
		}

		if (context.nodeToHover()instanceof ISymbolReferenceNode symbolReferenceNode)
		{
			if (symbolReferenceNode.reference()instanceof IVariableNode variableNode)
			{
				return hoverVariable(variableNode, context);
			}
		}

		return EMPTY_HOVER;
	}

	private Hover hoverWorkfileType()
	{
		var contentBuilder = MarkupContentBuilderFactory.newBuilder();
		contentBuilder.appendParagraph("Specifies the type of the work file.");
		contentBuilder.appendSection("Possible types", b -> b
			.appendBullet("`DEFAULT` - Determines the file type from the file extension.")
			.appendBullet("`TRANSFER`- Data connection for ENTIRE CONNECTION.")
			.appendBullet("`SAG` - Binary format.")
			.appendBullet("`ASCII` - Text files terminated by a carriage return (windows) and line feed.")
			.appendBullet("`ASCII-COMPRESSED` - ASCII but all whitespace removed.")
			.appendBullet("`ENTIRECONNECTION` - Data connection for ENTIRE CONNECTION.")
			.appendBullet("`UNFORMATTED` - Unformatted file, no format information is implied.")
			.appendBullet("`PORTABLE` - Portable file type between endian types.")
			.appendBullet("`CSV` - CSV file where each record is written to its own line."));

		return new Hover(contentBuilder.build());
	}

	private Hover hoverWorkfileAttributes()
	{
		var contentBuilder = MarkupContentBuilderFactory.newBuilder();
		contentBuilder.appendParagraph("Specifies file attributes for the defined work file.");
		contentBuilder.append("Multiple attributes can be specified by separating them by comma or whitespace. ");
		contentBuilder.append("There are 4 categories of attributes that can be applied. ");
		contentBuilder.appendItalic("If there are two attributes of the same category specified, only the last one is applied.");
		contentBuilder.appendNewline();
		contentBuilder.appendSection("Appending", b -> b
			.appendBullet("`NOAPPEND `- File is written from the start. Default value")
			.appendBullet("`APPEND `- Content is appended to the given file."));
		contentBuilder.appendSection("Keep/Delete", b -> b
			.appendBullet("`KEEP `- Keep the file when on CLOSE. Default value")
			.appendBullet("`DELETE `- Delete the work file on CLOSE."));
		contentBuilder.appendSection("Byte Order Mark", b -> b
			.appendBullet("`NOBOM `- Don't add a BOM. Default value")
			.appendBullet("`BOM `- Add a BOM in front of the content."));
		contentBuilder.appendSection("Carriage return", b -> b
			.appendBullet("`REMOVECR` - Remove carriage return characters on ASCII files. Default value")
			.appendBullet("`KEEPCR` - Keep carriage return characters."));

		return new Hover(contentBuilder.build());
	}

	private Hover hoverExternalModule(IModuleReferencingNode moduleReferencingNode)
	{
		var module = moduleReferencingNode.reference();
		if (module == null)
		{
			return EMPTY_HOVER;
		}

		var contentBuilder = MarkupContentBuilderFactory.newBuilder();
		contentBuilder.appendStrong("%s.%s".formatted(module.file().getLibrary().getName(), module.file().getReferableName())).appendNewline();
		/*
		if(module instanceof IFunction function)
		{
			TODO: Add return type
		}
		 */

		if (!module.file().getFilenameWithoutExtension().equals(module.file().getReferableName()))
		{
			contentBuilder.appendItalic("File: %s".formatted(module.file().getFilenameWithoutExtension())).appendNewline();
		}

		var documentation = module.moduleDocumentation();
		if (documentation != null && !documentation.trim().isEmpty())
		{
			contentBuilder.appendCode(documentation);
		}

		addModuleParameter(contentBuilder, module);

		return new Hover(contentBuilder.build());
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
		if (!declaration.comment.isEmpty())
		{
			contentBuilder.appendSection("comment", nestedBuilder -> nestedBuilder.appendCode(declaration.comment));
		}

		if (variable.isArray())
		{
			contentBuilder.appendSection("dimensions", nested ->
			{
				for (var dimension : variable.dimensions())
				{
					nested.appendBullet(dimension.displayFormat());
				}
			});
		}

		if (variable.level() > 1)
		{
			var owner = NodeUtil.findLevelOneParentOf(variable);
			contentBuilder.appendItalic("member of:");
			contentBuilder.appendNewline();
			contentBuilder.appendCode("%s %d %s".formatted(owner.scope().toString(), owner.level(), owner.name()));
		}

		addSourceFileIfNeeded(contentBuilder, variable.declaration(), context);
		return new Hover(contentBuilder.build());
	}

	private VariableDeclarationFormat formatVariableDeclaration(INaturalModule module, IVariableNode variable)
	{
		var declaration = "%s %d %s".formatted(variable.scope().toString(), variable.level(), variable.name());
		if (variable instanceof ITypedVariableNode typedVariableNode)
		{
			declaration += " %s".formatted(typedVariableNode.type().toShortString());
			if (typedVariableNode.type().initialValue() != null)
			{
				declaration += " %s<%s>".formatted(
					typedVariableNode.type().isConstant() ? "CONST" : "INIT",
					typedVariableNode.type().initialValue().source()
				);
			}
		}

		if (variable.findDescendantToken(SyntaxKind.OPTIONAL) != null)
		{
			declaration += " OPTIONAL";
		}

		var comment = module.extractLineComment(variable.position().line());
		return new VariableDeclarationFormat(declaration, comment);
	}

	private static void addSourceFileIfNeeded(IMarkupContentBuilder contentBuilder, IPosition hoveredPosition, HoverContext context)
	{
		if (!hoveredPosition.filePath().equals(context.file().getPath()))
		{
			contentBuilder.appendItalic("source:");
			contentBuilder.appendNewline();
			contentBuilder.append("- %s.%s", context.file().getLibrary().name(), hoveredPosition.fileNameWithoutExtension());
		}
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
			for (var parameterDefinition : hasDefineData.defineData().parameterInOrder())
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
