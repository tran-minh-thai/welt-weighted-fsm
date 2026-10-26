The wording about the GraMi baseline changed in comments and documentation only
(src/main/java/welt/core/MniSupportCounter.java, src/main/java/welt/strategy/GraMiStrategy.java, README.md):
"an independent reimplementation of the CSP-based MNI evaluation described by Elseidy et al. (PVLDB 2014);
no GraMi source code is included".

Bytecode check: every source file compiled with javac -g:none (no line numbers or local names), before
(commit afdf2dc) and after the change; SHA-256 of every class in classes_before.sha256 and
classes_after.sha256. 0 of 57 classes differ. Control: the same comparison after changing one exception
message in PivotVoter.java flags exactly 1 class (PivotVoter.class), so the comparison is not blind.
Java: openjdk version "26.0.1" 2026-04-21
