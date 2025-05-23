package org.amshove.natparse.parsing;

import org.amshove.natparse.ReadOnlyList;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.lexing.SyntaxToken;
import org.amshove.natparse.lexing.TokenList;
import org.amshove.natparse.natural.*;
import org.amshove.natparse.natural.project.NaturalFile;
import org.amshove.natparse.natural.project.NaturalFileType;
import org.amshove.natparse.natural.project.NaturalProgrammingMode;

import java.util.ArrayList;
import java.util.List;

public class NaturalParser
{
	private final IModuleProvider moduleProvider;

	public NaturalParser()
	{
		this(null);
	}

	public NaturalParser(IModuleProvider moduleProvider)
	{
		this.moduleProvider = moduleProvider;
	}

	public INaturalModule parse(NaturalFile file, TokenList tokens)
	{
		var moduleProviderToUse = moduleProvider;
		if (moduleProviderToUse == null)
		{
			moduleProviderToUse = new DefaultModuleProvider(file);
		}
		return parseModule(file, moduleProviderToUse, tokens);
	}

	private NaturalModule parseModule(NaturalFile file, IModuleProvider moduleProvider, TokenList tokens)
	{
		var naturalModule = new NaturalModule(file);
		naturalModule.addDiagnostics(tokens.diagnostics());
		var topLevelNodes = new ArrayList<ISyntaxNode>();
		naturalModule.setComments(tokens.comments());
		naturalModule.setTokens(tokens.allTokens());

		if (tokens.sourceHeader() != null && tokens.sourceHeader().isReportingMode())
		{
			naturalModule.addDiagnostic(ParserErrors.unsupportedProgrammingMode(tokens.sourceHeader().getProgrammingMode(), file.getPath()));
		}
		naturalModule.setHeader(tokens.sourceHeader());

		if (naturalModule.programmingMode() == NaturalProgrammingMode.REPORTING)
		{
			// REPORTING mode is not supported. If we can't deduce the mode, assume STRUCTURED.
			return naturalModule;
		}

		VariableNode functionReturnVariable = null;
		if (file.getFiletype() == NaturalFileType.FUNCTION) // skip over DEFINE FUNCTION
		{
			functionReturnVariable = consumeDefineFunction(tokens, naturalModule);
		}

		// Try to advance to DEFINE DATA.
		// If the module contains a DEFINE DATA, the TokenLists offset will be set to the start of DEFINE DATA.
		// This was introduced to temporarily skip over INCLUDE and OPTION before DEFINE DATA
		if (advanceToDefineData(tokens))
		{
			topLevelNodes.add(parseDefineData(tokens, moduleProvider, naturalModule));
			if (file.getFiletype() == NaturalFileType.FUNCTION && naturalModule.defineData() != null && functionReturnVariable != null)
			{
				var defineData = (DefineDataNode) naturalModule.defineData();
				defineData.addVariable(functionReturnVariable);
				naturalModule.addReferencableNodes(List.of(functionReturnVariable));
			}
		}

		if (file.getFiletype().canHaveBody())
		{
			var bodyParseResult = parseBody(tokens, moduleProvider, naturalModule);
			topLevelNodes.add(bodyParseResult.body());

			if (file.getFiletype() != NaturalFileType.COPYCODE)
			{
				// Copycodes will be analyzed in context of their including module.
				// Analyzing them doesn't make sense, because we can't check parameter
				// types etc.
				ExternalParameterCheck.performParameterCheck(naturalModule, bodyParseResult.moduleRefs());
			}
		}

		naturalModule.setSyntaxTree(SyntaxTree.create(ReadOnlyList.from(topLevelNodes)));

		return naturalModule;
	}

	private boolean advanceToDefineData(TokenList tokens)
	{
		SyntaxToken current;
		SyntaxToken next;
		for (var offset = 0; offset < tokens.size(); offset++)
		{
			current = tokens.peek(offset);
			next = tokens.peek(offset + 1);
			if (current != null && next != null && current.kind() == SyntaxKind.DEFINE && next.kind() == SyntaxKind.DATA)
			{
				tokens.advanceBy(offset);
				return true;
			}
		}

		return false;
	}

	private VariableNode consumeDefineFunction(TokenList tokens, NaturalModule naturalModule)
	{
		VariableNode functionReturnVariable = null;
		while (!tokens.isAtEnd())
		{
			if (tokens.peek().kind() == SyntaxKind.DEFINE && tokens.peek(1).kind() == SyntaxKind.DATA)
			{
				break;
			}

			if (tokens.peek(1).kind() == SyntaxKind.RETURNS)
			{
				var functionName = tokens.advance();
				naturalModule.setFunctionName(functionName);
				functionReturnVariable = new VariableNode();
				functionReturnVariable.setLevel(1);
				functionReturnVariable.setScope(VariableScope.LOCAL);
				functionReturnVariable.setDeclaration(new TokenNode(functionName));

				tokens.advance(); // RETURNS
				if (tokens.peek().kind() == SyntaxKind.LPAREN)
				{
					tokens.advance(); // (
					var typeTokenSource = tokens.advance().source();
					if (tokens.peek().kind() == SyntaxKind.COMMA || tokens.peek().kind() == SyntaxKind.DOT)
					{
						typeTokenSource += tokens.advance().source(); // decimal
						typeTokenSource += tokens.advance().source(); // next number
					}
					var type = DataType.fromString(typeTokenSource);
					var typedReturnVariable = new TypedVariableNode(functionReturnVariable);

					if (typeTokenSource.contains("/") || tokens.peek().kind() == SyntaxKind.SLASH)
					{
						var firstDimension = new ArrayDimension();
						// Parsing array dimensions is currently too tightly coupled into DefineDataParser,
						// so we do a rudimentary implementation to revisit later.
						firstDimension.setLowerBound(IArrayDimension.UNBOUND_VALUE);
						firstDimension.setUpperBound(IArrayDimension.UNBOUND_VALUE);
						typedReturnVariable.addDimension(firstDimension);
						while (tokens.peek().kind() != SyntaxKind.RPAREN && !tokens.isAtEnd())
						{
							if (tokens.peek().kind() == SyntaxKind.COMMA)
							{
								var nextDimension = new ArrayDimension();
								nextDimension.setLowerBound(IArrayDimension.UNBOUND_VALUE);
								nextDimension.setUpperBound(IArrayDimension.UNBOUND_VALUE);
								typedReturnVariable.addDimension(nextDimension);
							}
							tokens.advance();
						}
					}

					tokens.advance(); // )
					if (tokens.peek().kind() == SyntaxKind.DYNAMIC)
					{
						type = DataType.ofDynamicLength(type.format());
					}
					naturalModule.setReturnType(type);
					typedReturnVariable.setType(new VariableType(type));
					functionReturnVariable = typedReturnVariable;
				}
				advanceToDefineData(tokens);
				break;
			}

			tokens.advance();
		}

		return functionReturnVariable;
	}

	private IDefineData parseDefineData(TokenList tokens, IModuleProvider moduleProvider, NaturalModule naturalModule)
	{

		var defineDataParser = new DefineDataParser(moduleProvider);
		var result = defineDataParser.parse(tokens);
		naturalModule.addDiagnostics(result.diagnostics());
		var defineData = result.result();
		if (defineData != null)
		{
			naturalModule.setDefineData(defineData);
			naturalModule.addReferencableNodes(defineData.variables().stream().map(n -> (IReferencableNode) n).toList());
		}

		return defineData;
	}

	private BodyParseResult parseBody(TokenList tokens, IModuleProvider moduleProvider, NaturalModule naturalModule)
	{
		var statementParser = new StatementListParser(moduleProvider);
		var result = statementParser.parse(tokens);
		naturalModule.addReferencableNodes(statementParser.getReferencableNodes());
		addRelevantParserDiagnostics(naturalModule, result);
		naturalModule.setBody(result.result());
		resolveVariableReferences(statementParser, naturalModule);

		if (naturalModule.defineData() != null)
		{
			var typer = new TypeChecker();
			for (var diagnostic : typer.check(naturalModule.defineData()))
			{
				naturalModule.addDiagnostic(diagnostic);
			}
		}

		if (naturalModule.body() != null && naturalModule.file().getFiletype() != NaturalFileType.COPYCODE)
		{
			var endStatementFound = false;
			for (var statement : naturalModule.body().statements())
			{
				if (endStatementFound)
				{
					reportNoSourceCodeAfterEndStatementAllowed(naturalModule, statement);
					break;
				}
				endStatementFound = statement instanceof IEndNode;
			}
			if (!endStatementFound && naturalModule.body().statements().hasItems())
			{
				reportEndStatementMissing(naturalModule, naturalModule.body().statements().last());
			}

			var typer = new TypeChecker();
			for (var diagnostic : typer.check(naturalModule.body()))
			{
				naturalModule.addDiagnostic(diagnostic);
			}
		}

		return new BodyParseResult(result.result(), statementParser.moduleReferencingNodes());
	}

	private void addRelevantParserDiagnostics(NaturalModule naturalModule, ParseResult<IStatementListNode> result)
	{
		for (var diagnostic : result.diagnostics())
		{
			if (diagnostic.id().equals(ParserError.UNRESOLVED_MODULE.id()))
			{
				if (naturalModule.isTestCase() && diagnostic.message().contains("module TEARDOWN") || diagnostic.message().contains("module SETUP"))
				{
					// Skip these unresolved subroutines.
					// These are special cases for NatUnit, because it doesn't force you to implement them.
					// It however calls them if they're present.
					continue;
				}
			}

			if (naturalModule.file().getFiletype() == NaturalFileType.COPYCODE)
			{
				if (ParserError.isUnresolvedError(diagnostic.id()))
				{
					// When parsing a copycode we don't want to report any unresolved references, because we simply don't know
					// if they are declared where the copycode is used.
					// They do however get reported in the module including the copycode.
					continue;
				}
			}

			naturalModule.addDiagnostic(diagnostic);
		}
	}

	private void resolveVariableReferences(StatementListParser statementParser, NaturalModule module)
	{
		// This could actually be done in the StatementListParser when encountering
		// a possible reference. But that would need changes in the architecture, since
		// it does not know about declared variables.

		var defineData = module.defineData();
		if (defineData == null)
		{
			return;
		}

		var unresolvedAdabasArrayAccess = new ArrayList<ISymbolReferenceNode>();
		for (var unresolvedReference : statementParser.unresolvedSymbols())
		{
			if (unresolvedReference.parent() instanceof IAdabasIndexAccess)
			{
				unresolvedAdabasArrayAccess.add(unresolvedReference); // needs to be re-evaluated after, because it's parents need to be resolved
				continue;
			}

			if (unresolvedReference.referencingToken().symbolName().startsWith("&")
				|| (unresolvedReference.referencingToken().symbolName().contains(".")
					&& unresolvedReference.referencingToken().symbolName().split("\\.")[1].startsWith("&")))
			{
				// Copycode parameter
				continue;
			}

			if (tryFindAndReference(unresolvedReference.token().symbolName(), unresolvedReference, defineData, module))
			{
				continue;
			}

			if (unresolvedReference.token().symbolName().startsWith("+")
				&& tryFindAndReference(unresolvedReference.token().symbolName().substring(1), unresolvedReference, defineData, module))
			{
				// TODO(hack, expressions): This should be handled when parsing expressions.
				continue;
			}

			if (unresolvedReference.token().symbolName().startsWith("C*")
				&& tryFindAndReference(unresolvedReference.token().symbolName().substring(2), unresolvedReference, defineData, module))
			{
				continue;
			}

			if (unresolvedReference.token().symbolName().startsWith("T*")
				&& tryFindAndReference(unresolvedReference.token().symbolName().substring(2), unresolvedReference, defineData, module))
			{
				// TODO(hack, write-statement): This will be obsolete when the WRITE statement is parsed
				continue;
			}

			if (unresolvedReference.token().symbolName().startsWith("P*")
				&& tryFindAndReference(unresolvedReference.token().symbolName().substring(2), unresolvedReference, defineData, module))
			{
				// TODO(hack, write-statement): This will be obsolete when the WRITE statement is parsed
				continue;
			}

			if (module.file().getFiletype() == NaturalFileType.FUNCTION && unresolvedReference.referencingToken().symbolName().equals(module.name()))
			{
				continue;
			}

			if (unresolvedReference.token().kind() == SyntaxKind.IDENTIFIER)
			{
				reportUnresolvedReference(module, unresolvedReference);
			}
		}

		for (var unresolvedReference : unresolvedAdabasArrayAccess)
		{
			if (unresolvedReference.parent()instanceof IAdabasIndexAccess adabasIndexAccess
				&& adabasIndexAccess.parent()instanceof IVariableReferenceNode arrayRef
				&& arrayRef.reference()instanceof IVariableNode resolvedArray
				&& !resolvedArray.isInView())
			{
				var diagnostic = ParserErrors.variableQualificationNotAllowedHere(
					"Variable qualification is not allowed when not referring to a database array",
					adabasIndexAccess.diagnosticPosition()
				);

				if (!diagnostic.filePath().equals(module.file().getPath()))
				{
					diagnostic = diagnostic.relocate(unresolvedReference.diagnosticPosition());
				}
				module.addDiagnostic(diagnostic);
			}
			else
			{
				if (!tryFindAndReference(unresolvedReference.token().symbolName(), unresolvedReference, defineData, module))
				{
					reportUnresolvedReference(module, unresolvedReference);
				}
			}
		}
	}

	private void reportUnresolvedReference(NaturalModule module, ISymbolReferenceNode unresolvedReference)
	{
		var diagnostic = ParserErrors.unresolvedReference(unresolvedReference);
		if (!diagnostic.filePath().equals(module.file().getPath()))
		{
			diagnostic = diagnostic.relocate(unresolvedReference.diagnosticPosition());
		}
		module.addDiagnostic(diagnostic);
	}

	private void reportNoSourceCodeAfterEndStatementAllowed(NaturalModule module, IStatementNode statement)
	{
		var diagnostic = ParserErrors.noSourceCodeAllowedAfterEnd(statement);
		module.addDiagnostic(diagnostic);
	}

	private void reportEndStatementMissing(NaturalModule module, IStatementNode statement)
	{
		var diagnostic = ParserErrors.endStatementMissing(statement);
		module.addDiagnostic(diagnostic);
	}

	private boolean tryFindAndReference(String symbolName, ISymbolReferenceNode referenceNode, IDefineData defineData, NaturalModule module)
	{
		var foundVariables = ((DefineDataNode) defineData).findVariablesWithName(symbolName);

		if (foundVariables.size() > 1)
		{
			var possibleQualifications = new StringBuilder();
			for (var foundVariable : foundVariables)
			{
				possibleQualifications.append(foundVariable.qualifiedName()).append(" ");
			}

			if (defineData.findDdmField(symbolName) != null) // TODO: Currently only necessary here because we don't parse FIND or READ yet
			{
				return true;
			}

			if (areAllInAView(foundVariables) && tryFindAndReferenceInViewAccessibleByCurrentAdabasStatementNesting(referenceNode))
			{
				return true;
			}

			module.addDiagnostic(ParserErrors.ambiguousSymbolReference(referenceNode, possibleQualifications.toString()));
		}

		if (!foundVariables.isEmpty())
		{
			foundVariables.get(0).addReference(referenceNode);
			return true;
		}

		return defineData.findDdmField(symbolName) != null;
	}

	private boolean tryFindAndReferenceInViewAccessibleByCurrentAdabasStatementNesting(ISymbolReferenceNode referenceNode)
	{
		var adabasViewInAccess = getAdabasViewsInAccessAtNodePosition(referenceNode);
		if (adabasViewInAccess.isEmpty())
		{
			return false;
		}

		var variableCandidatesInViews = new ArrayList<IVariableNode>();
		for (var viewInAccess : adabasViewInAccess)
		{
			var maybeDeclaredVariable = viewInAccess.findVariable(referenceNode.referencingToken().symbolName());
			if (maybeDeclaredVariable != null)
			{
				variableCandidatesInViews.add(maybeDeclaredVariable);
			}
		}

		if (variableCandidatesInViews.size() == 1)
		{
			variableCandidatesInViews.get(0).addReference(referenceNode);
			return true;
		}

		return false;
	}

	private List<IViewNode> getAdabasViewsInAccessAtNodePosition(ISyntaxNode node)
	{
		var views = new ArrayList<IViewNode>();
		while (node.parent() != null)
		{
			var parent = node.parent();
			if (parent instanceof IAdabasAccessStatementNode adabasAccess)
			{
				views.add((IViewNode) adabasAccess.view().reference());
			}
			node = parent;
		}
		return views;
	}

	private boolean areAllInAView(List<IVariableNode> variables)
	{
		for (var foundVariable : variables)
		{
			if (!foundVariable.isInView())
			{
				return false;
			}
		}

		return true;
	}

	private record BodyParseResult(IStatementListNode body, List<IModuleReferencingNode> moduleRefs)
	{}
}
