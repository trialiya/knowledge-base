/**
 * Один репозиторий проекта и всё, что читает и пишет его рабочее дерево: {@code GitService} с его
 * записью ({@code GitWriter}), правила путей и видимости ({@code RepoPaths}, {@code VisibleFiles},
 * {@code Pathspec}), чтение байтов ({@code RepoFiles}), поиск ({@code GitGrep}) и диффы ({@code
 * Diffs}). Наружу торчат только {@code GitRegistry} — кто обслуживает данный проект — и сам {@code
 * GitService}; остальное package-private, потому что это внутренности одной операции над деревом.
 */
@NullMarked
package io.github.trialiya.kb.service.file.git;

import org.jspecify.annotations.NullMarked;
