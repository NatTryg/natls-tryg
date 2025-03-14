package org.amshove.natls.completion;

import org.amshove.natls.WorkspaceEditBuilder;
import org.amshove.natls.config.LSConfiguration;
import org.amshove.natls.hover.HoverContext;
import org.amshove.natls.hover.HoverProvider;
import org.amshove.natls.languageserver.LspUtil;
import org.amshove.natls.languageserver.UnresolvedCompletionInfo;
import org.amshove.natls.project.LanguageServerFile;
import org.amshove.natls.project.LanguageServerLibrary;
import org.amshove.natls.snippets.SnippetEngine;
import org.amshove.natparse.ReadOnlyList;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.natural.*;
import org.amshove.natparse.natural.builtin.BuiltInFunctionTable;
import org.amshove.natparse.natural.builtin.SystemFunctionDefinition;
import org.amshove.natparse.natural.project.NaturalFileType;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class CompletionProvider
{
	private static final Logger log = Logger.getAnonymousLogger();

	private final SnippetEngine snippetEngine;
	private final HoverProvider hoverProvider;
	private LSConfiguration config;

	public CompletionProvider(SnippetEngine snippetEngine, HoverProvider hoverProvider)
	{
		this.snippetEngine = snippetEngine;
		this.hoverProvider = hoverProvider;
	}

	public List<CompletionItem> prepareCompletion(
		LanguageServerFile file, CompletionParams params,
		LSConfiguration config
	)
	{
		this.config = config;
		if (!file.getType().canHaveBody())
		{
			return List.of();
		}
		var module = file.module();
		var completionContext = CodeCompletionContext.create(file, params.getPosition());

		var completionItems = new ArrayList<CompletionItem>();

		if (completionContext.completesDataArea())
		{
			return dataAreaCompletions(file.getLibrary());
		}

		if (completionContext.completesInclude())
		{
			completionItems.addAll(copycodeCompletions(file.getLibrary(), completionContext));
			return completionItems;
		}

		var isTriggeredByDot = params.getContext().getTriggerKind() == CompletionTriggerKind.TriggerCharacter && ".".equals(
			params.getContext().getTriggerCharacter()
		);
		if (isTriggeredByDot || completionContext.completesQualifiedName())
		{
			assert completionContext.currentToken() != null;
			assert completionContext.previousToken() != null;

			addPostfixCompletionItems(file, completionContext, module, completionItems);
			addQualifiedVariableCompletionItems(file, completionContext, completionItems, module);
			return completionItems;
		}

		if (completionContext.completesPerform() && !completionContext.completesParameter())
		{
			completionItems.addAll(externalSubroutineCompletions(file.getLibrary(), completionContext));
			completionItems.addAll(localSubroutineCompletions(module, completionContext));
			return completionItems;
		}

		if (completionContext.completesCallnat() && !completionContext.completesParameter())
		{
			completionItems.addAll(subprogramCompletions(file.getLibrary(), completionContext));
			return completionItems;
		}

		completionItems.addAll(snippetEngine.provideSnippets(file));

		completionItems.addAll(
			findVariablesToComplete(module)
				.map(v -> toVariableCompletion(v, module, file, ""))
				.filter(Objects::nonNull)
				.toList()
		);

		completionItems.addAll(localSubroutineCompletions(module, completionContext));

		completionItems.addAll(functionCompletions(file.getLibrary()));
		completionItems.addAll(externalSubroutineCompletions(file.getLibrary(), completionContext));
		completionItems.addAll(subprogramCompletions(file.getLibrary(), completionContext));

		completionItems.addAll(completeSystemVars(completionContext));

		return completionItems;
	}

	private void addQualifiedVariableCompletionItems(
		LanguageServerFile file, CodeCompletionContext completionContext,
		ArrayList<CompletionItem> completionItems, INaturalModule module
	)
	{
		assert completionContext.currentToken() != null;
		var qualifiedNameFilter = completionContext.currentToken().kind() == SyntaxKind.DOT
			? completionContext.previousTextsCombined()
			: completionContext.currentToken().symbolName(); // label identifier

		completionItems.addAll(
			findVariablesToComplete(module)
				.filter(v -> v.qualifiedName().startsWith(qualifiedNameFilter))
				.map(v -> toVariableCompletion(v, module, file, qualifiedNameFilter))
				.filter(Objects::nonNull)
				.toList()
		);
	}

	private static void addPostfixCompletionItems(
		LanguageServerFile file, CodeCompletionContext completionContext,
		INaturalModule module, ArrayList<CompletionItem> completionItems
	)
	{
		assert completionContext.currentToken() != null;
		assert completionContext.previousToken() != null;

		var identifierName = completionContext.isCurrentTokenKind(SyntaxKind.DOT)
			? completionContext.previousToken().symbolName()
			: completionContext.currentToken().symbolName()
				.substring(0, completionContext.currentToken().symbolName().length() - 1).toUpperCase();

		var maybeVariableInvokedOn = module.referencableNodes().stream()
			.filter(IVariableNode.class::isInstance)
			.map(IVariableNode.class::cast)
			.filter(v -> v.name().equals(identifierName) || v.qualifiedName().equals(identifierName))
			.findAny();

		if (maybeVariableInvokedOn.isEmpty())
		{
			return;
		}

		var variableInvokedOn = maybeVariableInvokedOn.get();

		// We append to the end of the token that is being completed
		// and delete the token afterward in an "additionalTextEdit".
		// That way the LSP specification for single line modification of
		// the range that the completion is invoked on is fulfilled.
		var rangeToInsert = LspUtil.toRange(completionContext.currentToken());
		rangeToInsert.setStart(rangeToInsert.getEnd());

		var rangeToDelete = completionContext.currentToken().kind() == SyntaxKind.DOT
			? LspUtil.toRangeSpanning(completionContext.previousToken(), completionContext.currentToken())
			: LspUtil.toRange(completionContext.currentToken());

		// delete token that is being completed
		var deleteEdit = new TextEdit(rangeToDelete, "");

		addIfPostfix(completionItems, identifierName, rangeToInsert, deleteEdit);
		addCompressPostfix(completionItems, identifierName, variableInvokedOn, rangeToInsert, deleteEdit);

		if (variableInvokedOn.isArray())
		{
			addForLoopPostfix(file, completionItems, identifierName, variableInvokedOn, rangeToInsert, deleteEdit);
			addOccPostfix(completionItems, identifierName, variableInvokedOn, rangeToInsert, deleteEdit);
			addCollectionMatchExpressionPostfix(completionItems, identifierName, variableInvokedOn, rangeToInsert, deleteEdit);
		}

		if (variableInvokedOn instanceof ITypedVariableNode typedVar && typedVar.type().emptyValue() != null)
		{
			addIsDefaultPostfix(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
			addCaseTranslationPostfix(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
			addValPostfix(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
			addIncrementDecrementPostfix(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
			addTrimPostfixes(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
			addScanAndMask(completionItems, typedVar, identifierName, rangeToInsert, deleteEdit);
		}

		if (variableInvokedOn.scope().isParameter() && variableInvokedOn.findDescendantToken(SyntaxKind.OPTIONAL) != null)
		{
			addIfSpecifiedPostfix(completionItems, identifierName, rangeToInsert, deleteEdit);
		}

	}

	private static void addCompressPostfix(ArrayList<CompletionItem> completionItems, String identifierName, IVariableNode variable, Range rangeToInsert, TextEdit deleteEdit)
	{
		var isProperGroup = variable instanceof IGroupNode && !(variable instanceof IRedefinitionNode);
		var wantsDelimiters = variable.isArray() || isProperGroup;

		var delimiters = wantsDelimiters ? " ${0:WITH ALL DELIMITER ';'}" : "${0}";
		completionItems.add(
			createSnippetPostfixCompletionItem(
				"compress",
				new TextEdit(rangeToInsert, "COMPRESS %s INTO ${1:#RESULT}%s".formatted(identifierAccess(identifierName, variable), delimiters)),
				deleteEdit
			)
		);
	}

	private static void addScanAndMask(
		ArrayList<CompletionItem> completionItems, ITypedVariableNode typedVar,
		String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		if (!typedVar.type().isAlphaNumericFamily() || typedVar.isArray())
		{
			return;
		}

		var scanEdit = new TextEdit(rangeToInsert, "%s = SCAN ${1:'VALUE'}${0}".formatted(identifierName));
		completionItems.add(
			createSnippetPostfixCompletionItem(
				"contains",
				scanEdit,
				deleteEdit
			)
		);

		var maskEdit = new TextEdit(rangeToInsert, "%s = MASK (${1:*})${0}".formatted(identifierName));
		completionItems.add(
			createSnippetPostfixCompletionItem(
				"matches",
				maskEdit,
				deleteEdit
			)
		);
	}

	private static void addIncrementDecrementPostfix(
		ArrayList<CompletionItem> completionItems,
		ITypedVariableNode typedVar, String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		if (!typedVar.type().isNumericFamily())
		{
			return;
		}

		var incrementEdit = new TextEdit(rangeToInsert, "ADD 1 TO %s".formatted(identifierName));
		var decrementEdit = new TextEdit(rangeToInsert, "SUBTRACT 1 FROM %s".formatted(identifierName));

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"increment", incrementEdit, deleteEdit
			)
		);
		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"decrement", decrementEdit, deleteEdit
			)
		);
	}

	private static void addTrimPostfixes(
		ArrayList<CompletionItem> completionItems, ITypedVariableNode typedVar,
		String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		if (!typedVar.type().isAlphaNumericFamily())
		{
			return;
		}

		var trimTrailingEdit = new TextEdit(rangeToInsert, "*TRIM(%s, TRAILING)".formatted(identifierName));
		var trimLeadingEdit = new TextEdit(rangeToInsert, "*TRIM(%s, LEADING)".formatted(identifierName));
		var trimEdit = new TextEdit(rangeToInsert, "*TRIM(%s)".formatted(identifierName));

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"trim", trimEdit, deleteEdit
			)
		);
		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"trimLeading", trimLeadingEdit, deleteEdit
			)
		);
		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"trimTrailing", trimTrailingEdit, deleteEdit
			)
		);
	}

	private static CompletionItem createPlainTextPostfixCompletionItem(String label, TextEdit edit, TextEdit deleteEdit)
	{
		var item = new CompletionItem(label);
		item.setTextEdit(Either.forLeft(edit));
		item.setKind(CompletionItemKind.Snippet);
		item.setInsertTextFormat(InsertTextFormat.PlainText);
		var additionalTextEdits = new ArrayList<TextEdit>();
		additionalTextEdits.add(deleteEdit);
		item.setAdditionalTextEdits(additionalTextEdits);
		return item;
	}

	private static CompletionItem createSnippetPostfixCompletionItem(String label, TextEdit edit, TextEdit deleteEdit)
	{
		var item = createPlainTextPostfixCompletionItem(label, edit, deleteEdit);
		item.setInsertTextFormat(InsertTextFormat.Snippet);
		return item;
	}

	private static void addValPostfix(
		ArrayList<CompletionItem> completionItems, ITypedVariableNode typedVar,
		String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		if (!typedVar.type().isAlphaNumericFamily())
		{
			return;
		}

		var edit = new TextEdit(rangeToInsert, "VAL(%s)".formatted(identifierName));
		completionItems.add(
			createPlainTextPostfixCompletionItem("val", edit, deleteEdit)
		);
	}

	private static void addCaseTranslationPostfix(
		ArrayList<CompletionItem> completionItems, ITypedVariableNode typedVar,
		String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		if (!typedVar.type().isAlphaNumericFamily())
		{
			return;
		}

		var upperEdit = new TextEdit(rangeToInsert, "*TRANSLATE(%s, UPPER)".formatted(identifierName));
		var lowerEdit = new TextEdit(rangeToInsert, "*TRANSLATE(%s, LOWER)".formatted(identifierName));

		completionItems.add(
			createPlainTextPostfixCompletionItem("toUpperCase", upperEdit, deleteEdit)
		);
		completionItems.add(
			createPlainTextPostfixCompletionItem("toLowerCase", lowerEdit, deleteEdit)
		);
	}

	private static void addOccPostfix(ArrayList<CompletionItem> completionItems, String identifierName, IVariableNode variableInvokedOn, Range rangeToInsert, TextEdit deleteEdit)
	{
		var occVar = variableInvokedOn instanceof IGroupNode group
			? group.variables().first().qualifiedName()
			: identifierName;

		var edit = new TextEdit(rangeToInsert, "*OCC(%s)".formatted(occVar));
		completionItems.add(
			createSnippetPostfixCompletionItem("occ", edit, deleteEdit)
		);
	}

	private static void addCollectionMatchExpressionPostfix(ArrayList<CompletionItem> completionItems, String identifierName, IVariableNode variableInvokedOn, Range rangeToInsert, TextEdit deleteEdit)
	{
		if (!(variableInvokedOn instanceof ITypedVariableNode typed))
		{
			return;
		}
		var defaultValue = typed.type().emptyValue();

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"contains",
				new TextEdit(rangeToInsert, "%s = %s".formatted(identifierAccess(identifierName, variableInvokedOn), defaultValue)),
				deleteEdit
			)
		);

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"noneIs",
				new TextEdit(rangeToInsert, "NOT %s = %s".formatted(identifierAccess(identifierName, variableInvokedOn), defaultValue)),
				deleteEdit
			)
		);

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"anyIsNot",
				new TextEdit(rangeToInsert, "%s <> %s".formatted(identifierAccess(identifierName, variableInvokedOn), defaultValue)),
				deleteEdit
			)
		);

		completionItems.add(
			createPlainTextPostfixCompletionItem(
				"allAre",
				new TextEdit(rangeToInsert, "NOT %s <> %s".formatted(identifierAccess(identifierName, variableInvokedOn), defaultValue)),
				deleteEdit
			)
		);
	}

	private static void addIfSpecifiedPostfix(ArrayList<CompletionItem> completionItems, String identifierName, Range rangeToInsert, TextEdit deleteEdit)
	{
		var edit = new TextEdit(rangeToInsert, """
			IF %s SPECIFIED
			  ${0:IGNORE}
			END-IF""".formatted(identifierName));
		completionItems.add(
			createSnippetPostfixCompletionItem("ifSpecified", edit, deleteEdit)
		);
	}

	private static void addIfPostfix(
		List<CompletionItem> completionItems, String identifierName, Range rangeToInsert,
		TextEdit deleteEdit
	)
	{
		var edit = new TextEdit(rangeToInsert, """
			IF %s$1
			  ${0:IGNORE}
			END-IF""".formatted(identifierName));
		completionItems.add(
			createSnippetPostfixCompletionItem("if", edit, deleteEdit)
		);
	}

	private static void addForLoopPostfix(
		LanguageServerFile file, List<CompletionItem> completionItems,
		String identifierName, IVariableNode variableInvokedOn, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		var sanitizedName = identifierName.replace(".", "-");

		var occVar = variableInvokedOn instanceof IGroupNode group
			? group.variables().first().qualifiedName()
			: identifierName;

		var edit = new TextEdit(rangeToInsert, """
			#S-%s := *OCC(%s)
			FOR #I-%s := 1 TO #S-%s
			  ${0:IGNORE}
			END-FOR""".formatted(sanitizedName, occVar, sanitizedName, sanitizedName));
		var item = createSnippetPostfixCompletionItem("for", edit, deleteEdit);
		var editBuilder = new WorkspaceEditBuilder();
		editBuilder
			.addsVariable(file, "#S-%s".formatted(sanitizedName), "(I4)", VariableScope.LOCAL)
			.addsVariable(file, "#I-%s".formatted(sanitizedName), "(I4)", VariableScope.LOCAL);
		var workspaceEdit = editBuilder.build();
		if (workspaceEdit.getChanges().containsKey(file.getUri()))
		{
			item.getAdditionalTextEdits().addAll(workspaceEdit.getChanges().get(file.getUri()));
		}

		completionItems.add(item);
	}

	private static void addIsDefaultPostfix(
		List<CompletionItem> completionItems, ITypedVariableNode typedVar,
		String identifierName, Range rangeToInsert, TextEdit deleteEdit
	)
	{
		var defaultValue = typedVar.type().emptyValue();
		var identifierAccess = identifierAccess(identifierName, typedVar);

		var edit = new TextEdit(rangeToInsert, """
			IF %s = %s
			  ${0:IGNORE}
			END-IF""".formatted(identifierAccess, defaultValue));

		completionItems.add(
			createSnippetPostfixCompletionItem("ifDefault", edit, deleteEdit)
		);
	}

	private List<CompletionItem> localSubroutineCompletions(INaturalModule module, CodeCompletionContext context)
	{
		return module.referencableNodes().stream()
			.filter(ISubroutineNode.class::isInstance)
			.map(ISubroutineNode.class::cast)
			.map(n -> this.createLocalSubroutineCompletionItem(n, context))
			.toList();
	}

	private List<CompletionItem> dataAreaCompletions(LanguageServerLibrary library)
	{
		var dataAreas = new ArrayList<>(library.getModulesOfType(NaturalFileType.LDA, true));
		var pdas = library.getModulesOfType(NaturalFileType.PDA, true);
		dataAreas.addAll(pdas);
		dataAreas.addAll(library.getModulesOfType(NaturalFileType.GDA, true));
		return dataAreas.stream()
			.map(f ->
			{
				var item = new CompletionItem(f.getReferableName());
				item.setInsertText(f.getReferableName());
				item.setKind(CompletionItemKind.Struct);
				return item;
			})
			.toList();
	}

	public CompletionItem resolveComplete(
		CompletionItem item, LanguageServerFile calledModulesFile,
		UnresolvedCompletionInfo info, LSConfiguration config
	)
	{
		this.config = config;

		if (item.getData() == null)
		{
			return item;
		}

		var module = calledModulesFile.module();
		if (module == null)
		{
			// Happens when the module this is called on has unrecoverable errors
			return item;
		}

		return switch (item.getKind())
		{
			case Variable ->
			{
				if (!(module instanceof IHasDefineData hasDefineData))
				{
					yield item;
				}

				var variableNode = hasDefineData.defineData().variables().stream()
					.filter(v -> v.qualifiedName().equals(info.getQualifiedName())).findFirst().orElse(null);
				if (variableNode == null)
				{
					yield item;
				}

				item.setDocumentation(
					new MarkupContent(
						MarkupKind.MARKDOWN,
						hoverProvider.createHover(
							new HoverContext(variableNode, variableNode.declaration(), calledModulesFile)
						).getContents()
							.getRight().getValue()
					)
				);
				yield item;
			}
			case Function ->
			{
				item.setInsertTextFormat(InsertTextFormat.Snippet);
				item.setDocumentation(
					new MarkupContent(
						MarkupKind.MARKDOWN,
						hoverProvider.hoverModule(module).getContents().getRight().getValue()
					)
				);
				item.setInsertText(
					"%s(<%s>)$0".formatted(
						calledModulesFile.getReferableName(),
						functionParameterListAsSnippet(calledModulesFile)
					)
				);
				yield item;
			}
			case Event ->
			{
				item.setInsertTextFormat(InsertTextFormat.Snippet);
				item.setDocumentation(
					new MarkupContent(
						MarkupKind.MARKDOWN,
						hoverProvider.hoverModule(module).getContents().getRight().getValue()
					)
				);
				var perform = info.hasPreviousText("PERFORM") ? "" : "PERFORM ";
				item.setInsertText(
					"%s%s%s%n$0".formatted(
						perform, calledModulesFile.getReferableName(),
						externalModuleParameterListAsSnippet(calledModulesFile)
					)
				);
				yield item;
			}
			case Class ->
			{
				item.setInsertTextFormat(InsertTextFormat.Snippet);
				item.setDocumentation(
					new MarkupContent(
						MarkupKind.MARKDOWN,
						hoverProvider.hoverModule(module).getContents().getRight().getValue()
					)
				);
				var callnat = info.hasPreviousText("CALLNAT") ? "" : "CALLNAT ";
				if (item.getTextEdit() != null && item.getTextEdit().isLeft())
				{
					item.getTextEdit().getLeft().setNewText(
						"%s'%s%n$0".formatted(
							calledModulesFile.getReferableName(),
							externalModuleParameterListAsSnippet(calledModulesFile)
						)
					);
				}
				else
				{
					item.setInsertText(
						"%s'%s'%s%n$0".formatted(
							callnat, calledModulesFile.getReferableName(),
							externalModuleParameterListAsSnippet(calledModulesFile)
						)
					);
				}
				yield item;
			}
			default -> item;
		};
	}

	private CompletionItem toVariableCompletion(
		IVariableNode variableNode, INaturalModule module,
		LanguageServerFile file, String alreadyPresentText
	)
	{
		try
		{
			var item = createCompletionItem(variableNode, file, module.referencableNodes(), !alreadyPresentText.isEmpty());
			item.setLabel(item.getLabel().replace(alreadyPresentText, ""));
			item.setInsertText(item.getInsertText().substring(alreadyPresentText.length()));
			if (item.getKind() == CompletionItemKind.Variable)
			{
				item.setData(new UnresolvedCompletionInfo((String) item.getData(), file.getPath().toUri().toString()));
			}
			item.setSortText("1");
			return item;
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "Variable completion threw an exception", e);
			return null;
		}
	}

	private Stream<IVariableNode> findVariablesToComplete(INaturalModule module)
	{
		return module.referencableNodes().stream()
			.filter(IVariableNode.class::isInstance)
			.map(IVariableNode.class::cast)
			.filter(
				v -> !(v instanceof IRedefinitionNode)
			); // this is the `REDEFINE #VAR`, which results in the variable being doubled in completion
	}

	private Collection<? extends CompletionItem> functionCompletions(LanguageServerLibrary library)
	{
		return library.getModulesOfType(NaturalFileType.FUNCTION, true)
			.stream()
			.map(f ->
			{
				try
				{
					var item = new CompletionItem(f.getReferableName());
					item.setData(new UnresolvedCompletionInfo(f.getReferableName(), f.getUri()));
					item.setKind(CompletionItemKind.Function);
					item.setSortText("5");
					return item;
				}
				catch (Exception e)
				{
					log.log(Level.SEVERE, "Function completion threw an exception", e);
					return null;
				}
			})
			.filter(Objects::nonNull)
			.toList();
	}

	private String functionParameterListAsSnippet(LanguageServerFile function)
	{
		if (!(function.module()instanceof IHasDefineData hasDefineData) || hasDefineData.defineData() == null)
		{
			return "";
		}

		var builder = new StringBuilder();
		var index = 1;
		for (var parameter : hasDefineData.defineData().declaredParameterInOrder())
		{
			if (index > 1)
			{
				builder.append(", ");
			}
			var parameterName = parameter instanceof IUsingNode using ? using.target().symbolName() : ((IVariableNode) parameter).name();
			builder.append("${%d:%s}".formatted(index, parameterName));
			index++;
		}

		return builder.toString();
	}

	private String externalModuleParameterListAsSnippet(LanguageServerFile module)
	{
		if (!(module.module()instanceof IHasDefineData hasDefineData) || hasDefineData.defineData() == null)
		{
			return "";
		}

		var builder = new StringBuilder();
		var index = 1;
		for (var parameter : hasDefineData.defineData().declaredParameterInOrder())
		{
			builder.append(" ");
			var parameterName = parameter instanceof IUsingNode using ? using.target().symbolName() : ((IVariableNode) parameter).name();
			builder.append("${%d:%s}".formatted(index, parameterName));
			index++;
		}

		return builder.toString();
	}

	private Collection<? extends CompletionItem> subprogramCompletions(
		LanguageServerLibrary library,
		CodeCompletionContext context
	)
	{
		return library.getModulesOfType(NaturalFileType.SUBPROGRAM, true)
			.stream()
			.map(f ->
			{
				try
				{
					var item = new CompletionItem(f.getReferableName());
					item.setData(createUnresolvedInfo(f, context));
					item.setKind(CompletionItemKind.Class);
					item.setSortText("5");
					if (context.isCurrentTokenKind(SyntaxKind.STRING_LITERAL))
					{
						item.setTextEdit(
							Either.forLeft(
								new TextEdit(
									LspUtil.toRangeSpanning(context.originalPosition(), context.currentToken()),
									""
								)
							)
						);
					}
					return item;
				}
				catch (Exception e)
				{
					log.log(Level.SEVERE, "Subprogram completion threw an exception", e);
					return null;
				}
			})
			.filter(Objects::nonNull)
			.toList();
	}

	private Collection<? extends CompletionItem> copycodeCompletions(
		LanguageServerLibrary library,
		CodeCompletionContext context
	)
	{
		return library.getModulesOfType(NaturalFileType.COPYCODE, true)
			.stream()
			.map(f ->
			{
				try
				{
					var item = new CompletionItem(f.getReferableName());
					item.setInsertText(f.getReferableName());
					item.setKind(CompletionItemKind.Module);
					return item;
				}
				catch (Exception e)
				{
					log.log(Level.SEVERE, "Copycode completion threw an exception", e);
					return null;
				}
			})
			.filter(Objects::nonNull)
			.toList();
	}

	private Collection<? extends CompletionItem> externalSubroutineCompletions(
		LanguageServerLibrary library,
		CodeCompletionContext context
	)
	{
		return library.getModulesOfType(NaturalFileType.SUBROUTINE, true)
			.stream()
			.map(f ->
			{
				try
				{
					var item = new CompletionItem(f.getReferableName());
					item.setData(createUnresolvedInfo(f, context));
					item.setKind(CompletionItemKind.Event);
					item.setSortText("5");
					return item;
				}
				catch (Exception e)
				{
					log.log(Level.SEVERE, "External Subroutine completion threw an exception", e);
					return null;
				}
			})
			.filter(Objects::nonNull)
			.toList();
	}

	private CompletionItem createCompletionItem(
		IVariableNode variableNode, LanguageServerFile openFile,
		ReadOnlyList<IReferencableNode> referencableNodes, boolean forceQualification
	)
	{
		var item = new CompletionItem();
		var variableName = variableNode.name();

		if (forceQualification || config.getCompletion().isQualify() || referencableNodes.stream()
			.filter(n -> n.declaration().symbolName().equals(variableNode.name())).count() > 1)
		{
			variableName = variableNode.qualifiedName();
		}

		var insertText = variableNode.dimensions().hasItems()
			? "%s($1)$0".formatted(variableName)
			: variableName;
		item.setInsertText(insertText);
		item.setInsertTextFormat(InsertTextFormat.Snippet);

		item.setKind(CompletionItemKind.Variable);
		item.setLabel(variableName);
		var label = "";
		if (variableNode instanceof ITypedVariableNode typedNode)
		{
			label = variableName + " :" + typedNode.formatTypeForDisplay();
		}
		if (variableNode instanceof IGroupNode)
		{
			label = variableName + " : Group";
		}

		var isImported = variableNode.position().filePath().equals(openFile.getPath());

		if (isImported)
		{
			label += " (%s)".formatted(variableNode.position().fileNameWithoutExtension());
		}

		item.setSortText(
			isImported
				? "2"
				: "3"
		);

		item.setLabel(label);
		item.setData(variableNode.qualifiedName());

		return item;
	}

	private CompletionItem createLocalSubroutineCompletionItem(
		ISubroutineNode subroutineNode,
		CodeCompletionContext context
	)
	{
		var item = new CompletionItem();
		item.setKind(CompletionItemKind.Method);
		var perform = context.isCurrentTokenKind(SyntaxKind.PERFORM) || context.isPreviousTokenKind(SyntaxKind.PERFORM)
			? ""
			: "PERFORM ";
		item.setInsertText(perform + subroutineNode.declaration().trimmedSymbolName(32));
		item.setLabel(subroutineNode.declaration().symbolName());
		item.setSortText("4");

		return item;
	}

	private List<CompletionItem> completeSystemVars(CodeCompletionContext context)
	{
		var alreadyContainsAsterisk = context.isCurrentTokenKind(SyntaxKind.ASTERISK) || context.isPreviousTokenKind(SyntaxKind.ASTERISK);
		return Arrays.stream(SyntaxKind.values())
			.filter(sk -> sk.isSystemVariable() || sk.isSystemFunction())
			.map(sk ->
			{
				var callableName = sk.toString().replace("SV_", "").replace("_", "-");
				var completionItem = new CompletionItem();
				var definition = BuiltInFunctionTable.getDefinition(sk);
				var label = "*" + callableName;
				var insertion = alreadyContainsAsterisk ? callableName : label;
				completionItem.setDetail(definition.documentation());
				completionItem.setKind(definition instanceof SystemFunctionDefinition ? CompletionItemKind.Function : CompletionItemKind.Variable);
				completionItem.setLabel(label + " :%s".formatted(definition.type().toShortString()));
				completionItem.setSortText(
					(alreadyContainsAsterisk ? "0" : "2") + completionItem.getLabel()
				); // if alreadyContainsAsterisk, bring them to the front. else to the end.

				completionItem.setInsertText(insertion);
				if (definition instanceof SystemFunctionDefinition functionDefinition && !functionDefinition.parameter()
					.isEmpty())
				{
					completionItem.setInsertText(insertion + "($1)$0");
					completionItem.setInsertTextFormat(InsertTextFormat.Snippet);
				}

				return completionItem;
			})
			.toList();

	}

	private UnresolvedCompletionInfo createUnresolvedInfo(LanguageServerFile file, CodeCompletionContext context)
	{
		var info = new UnresolvedCompletionInfo(file.getReferableName(), file.getUri());
		info.setPreviousText(context.previousTexts());
		return info;
	}

	private static String identifierAccess(String identifierName, IVariableNode node)
	{
		var access = identifierName;
		return node.isArray()
			? String.format("%s(%s)", access, "*" + ", *".repeat(node.dimensions().size() - 1))
			: access;
	}

}
