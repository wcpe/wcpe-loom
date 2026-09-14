ruleset {
    UnnecessarySemicolon
    BlockEndsWithBlankLine
    BlockStartsWithBlankLine
    ConsecutiveBlankLines
    MissingBlankLineAfterImports
    MissingBlankLineAfterPackage

    // Braces
    BracesForClass
    BracesForForLoop
    BracesForIfElse
    BracesForMethod
    BracesForTryCatchFinally

    // Spaces
    SpaceAfterCatch
    SpaceAfterComma
    SpaceAfterClosingBrace
    SpaceAfterFor
    SpaceAfterIf
    SpaceAfterOpeningBrace
    SpaceAfterSemicolon
    SpaceAfterSwitch
    SpaceAfterWhile
    SpaceAroundClosureArrow
    SpaceAroundMapEntryColon(characterAfterColonRegex: /\ /)
    SpaceAroundOperator
    SpaceBeforeClosingBrace
    SpaceBeforeOpeningBrace
    TrailingWhitespace

    // Groovyism - See: https://codenarc.org/codenarc-rules-groovyism.html
    ClosureAsLastMethodParameter
    ExplicitArrayListInstantiation
    ExplicitCallToAndMethod
    ExplicitCallToCompareToMethod
    ExplicitCallToDivMethod
    ExplicitCallToEqualsMethod
    ExplicitCallToGetAtMethod
    ExplicitCallToLeftShiftMethod
    ExplicitCallToMinusMethod
    ExplicitCallToMultiplyMethod
    ExplicitCallToModMethod
    ExplicitCallToOrMethod
    ExplicitCallToPlusMethod
    ExplicitCallToPowerMethod
    ExplicitCallToRightShiftMethod
    ExplicitCallToXorMethod
    ExplicitHashMapInstantiation
    ExplicitLinkedHashMapInstantiation
    ExplicitHashSetInstantiation
    ExplicitLinkedListInstantiation
    ExplicitStackInstantiation
    ExplicitTreeSetInstantiation
    // GetterMethodCouldBeProperty: 测试中 BuildService.getParameters() 是接口实现，codenarc 误报，排除
    GetterMethodCouldBeProperty(enabled: false)
    GStringAsMapKey
    GStringExpressionWithinString
    CouldBeElvis
    TernaryCouldBeElvis
    FieldTypeRequired
    MethodParameterTypeRequired

    // Imports
    UnusedImport
    UnnecessaryGroovyImport
    NoWildcardImports(ignoreStaticImports: true)
    ImportFromSamePackage
    DuplicateImport

    //Misc
    LongLiteralWithLowerCaseL
}
