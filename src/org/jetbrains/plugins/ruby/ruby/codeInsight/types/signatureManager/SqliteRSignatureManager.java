package org.jetbrains.plugins.ruby.ruby.codeInsight.types.signatureManager;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtilRt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.ruby.gem.GemInfo;
import org.jetbrains.plugins.ruby.gem.util.GemSearchUtil;
import org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.structure.SymbolUtil;
import org.jetbrains.plugins.ruby.ruby.codeInsight.symbols.v2.ClassModuleSymbol;
import org.jetbrains.plugins.ruby.ruby.codeInsight.types.CoreTypes;
import org.jetbrains.plugins.ruby.ruby.codeInsight.types.graph.RSignatureDAG;
import org.jetbrains.plugins.ruby.ruby.codeInsight.types.signature.ParameterInfo;
import org.jetbrains.plugins.ruby.ruby.codeInsight.types.signature.RSignature;
import org.jetbrains.plugins.ruby.ruby.codeInsight.types.signature.RSignatureBuilder;
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.Visibility;

import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class SqliteRSignatureManager extends RSignatureManager {
    @NotNull
    private static final Logger LOG = Logger.getInstance(SqliteRSignatureManager.class.getName());

    @Nullable
    private static RSignatureManager ourInstance;

    @NotNull
    private final Connection myConnection;

    @Nullable
    public static RSignatureManager getInstance() {
        if (ourInstance == null) {
            try {
                final URL dbURL = SqliteRSignatureManager.class.getClassLoader().getResource("CallStat.db");
                if (dbURL != null) {
                    ourInstance = new SqliteRSignatureManager(dbURL.getPath());
                }
            } catch (ClassNotFoundException | SQLException e) {
                LOG.info(e);
                return null;
            }
        }

        return ourInstance;
    }

    private SqliteRSignatureManager(@NotNull final String dbPath) throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        myConnection = DriverManager.getConnection(String.format("jdbc:sqlite:%s", dbPath));
    }

    @NotNull
    @Override
    public List<String> findReturnTypeNamesBySignature(@NotNull final Project project, @Nullable final Module module,
                                                       @NotNull final RSignature signature) {
        final String sql = String.format("SELECT * FROM signatures WHERE method_name = '%s' AND receiver_name = '%s';",
                                         signature.getMethodName(), signature.getReceiverName());
        final List<RSignature> signatures = executeQuery(sql);
        final List<Pair<RSignature, Integer>> signaturesAndDistances = signatures.stream()
                .map(sign -> Pair.create(sign, RSignatureDAG.getArgsTypeNamesDistance(project, signature.getArgsTypeName(),
                                                                                      sign.getArgsTypeName())))
                .collect(Collectors.toList());

        signaturesAndDistances.removeIf(signAndFist -> signAndFist.getSecond() == null);
        if (signaturesAndDistances.isEmpty()) {
            return ContainerUtilRt.emptyList();
        }

        if (checkIfOnlyOneUniqueReturnTypeName(signaturesAndDistances)) {
            return new ArrayList<String>() {{
                add(signaturesAndDistances.get(0).getFirst().getReturnTypeName());
            }};
        }

        final String gemName = signaturesAndDistances.get(0).getFirst().getGemName();
        final String moduleGemVersion = getGemVersionByName(module, gemName);
        filterSignaturesByModuleGemVersion(moduleGemVersion, signaturesAndDistances);

        if (checkIfOnlyOneUniqueReturnTypeName(signaturesAndDistances)) {
            return new ArrayList<String>() {{
                add(signaturesAndDistances.get(0).getFirst().getReturnTypeName());
            }};
        }

        final int minDistance = signaturesAndDistances.stream()
                .mapToInt(pair -> pair.getSecond())
                .min()
                .getAsInt();
        signaturesAndDistances.removeIf(signAndDist -> signAndDist.getSecond() > minDistance);
        return signaturesAndDistances.stream()
                .map(pair -> pair.getFirst().getReturnTypeName())
                .collect(Collectors.toList());
    }

    @Override
    public void recordSignature(@NotNull final RSignature signature) {
        final String argsInfoSerialized = signature.getArgsInfo().stream()
                .map(argInfo -> String.join(",", argInfo.getType().toString().toLowerCase(), argInfo.getName(),
                                                 argInfo.getDefaultValueTypeName()))
                .collect(Collectors.joining(";"));
        final String sql = String.format("INSERT OR REPLACE INTO signatures " +
                                         "values('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s');",
                                         signature.getMethodName(), signature.getReceiverName(),
                                         String.join(";", signature.getArgsTypeName()), argsInfoSerialized,
                                         signature.getReturnTypeName(), signature.getGemName(), signature.getGemVersion(),
                                         signature.getVisibility());
        executeUpdate(sql);
    }

    @Override
    public void deleteSignature(@NotNull final RSignature signature) {
        final String sql = String.format("DELETE FROM signatures WHERE args_type_name = '%s' " +
                                         "AND method_name = '%s' AND receiver_name = '%s' " +
                                         "AND gem_name = '%s' AND gem_version = '%s';",
                                         String.join(";", signature.getArgsTypeName()),
                                         signature.getMethodName(), signature.getReceiverName(),
                                         signature.getGemName(), signature.getGemVersion());
        executeUpdate(sql);
    }

    @Override
    public void compact(@NotNull final Project project) {
        mergeRecordsWithSameSignatureButDifferentReturnTypeNames(project);
        mergeRecordsWithDifferentSignaturesButSameReturnTypeName(project);
        // TODO: infer code contracts
    }

    @Override
    public void clear() {
        final String sql = "DELETE FROM signatures;";
        executeUpdate(sql);
    }

    @NotNull
    @Override
    public List<ParameterInfo> getMethodArgsInfo(@NotNull final String methodName, @Nullable final String receiverName) {
        final String sql = String.format("SELECT args_info FROM signatures WHERE method_name = '%s' AND receiver_name = '%s';",
                                         methodName, receiverName);
        try (final Statement statement = myConnection.createStatement()) {
            final ResultSet resultSet = statement.executeQuery(sql);
            if (resultSet.next()) {
                return parseArgsInfo(resultSet.getString("args_info"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            LOG.info(e);
        }

        return ContainerUtilRt.emptyList();
    }

    @NotNull
    @Override
    protected Set<RSignature> getReceiverMethodSignatures(@NotNull final String receiverName) {
        final String sql = String.format("SELECT * FROM signatures WHERE receiver_name = '%s';", receiverName);
        final List<RSignature> signatures = executeQuery(sql);
        return new HashSet<>(signatures);
    }

    @NotNull
    private static String getGemVersionByName(@Nullable final Module module, @NotNull final String gemName) {
        if (module != null && !gemName.isEmpty()) {
            final GemInfo gemInfo = GemSearchUtil.findGemEx(module, gemName);
            if (gemInfo != null) {
                return StringUtil.notNullize(gemInfo.getRealVersion());
            }
        }

        return "";
    }

    @NotNull
    private static List<ParameterInfo> parseArgsInfo(@NotNull final String argsInfoSerialized) {
        try {
            return StringUtil.splitHonorQuotes(argsInfoSerialized, ';').stream()
                    .map(argInfo -> StringUtil.splitHonorQuotes(argInfo, ','))
                    .map(argInfo -> new ParameterInfo(argInfo.get(1),
                                                      ParameterInfo.Type.valueOf(argInfo.get(0).toUpperCase()),
                                                      argInfo.get(2)))
                    .collect(Collectors.toList());
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean checkIfOnlyOneUniqueReturnTypeName(@NotNull final List<Pair<RSignature, Integer>>
                                                              signaturesAndReturnTypeNames) {
        final long countOfDistinctReturnTypeNames = signaturesAndReturnTypeNames.stream()
                .map(pair -> pair.getFirst().getReturnTypeName())
                .distinct()
                .count();
        return countOfDistinctReturnTypeNames == 1;
    }

    private static void filterSignaturesByModuleGemVersion(@NotNull final String moduleGemVersion,
                                                           @NotNull final List<Pair<RSignature, Integer>> signaturesAndDistances) {
        final List<String> gemVersions = signaturesAndDistances.stream()
                .map(pair -> pair.getFirst())
                .map(RSignature::getGemVersion)
                .collect(Collectors.toList());
        final String closestGemVersion = getClosestGemVersion(moduleGemVersion, gemVersions);
        signaturesAndDistances.removeIf(signAndDist -> {
            final String gemVersion = signAndDist.getFirst().getGemVersion();
            return !gemVersion.equals(closestGemVersion);
        });
    }

    private void mergeRecordsWithSameSignatureButDifferentReturnTypeNames(@NotNull final Project project) {
        final String sql = "SELECT DISTINCT * FROM signatures " +
                           "GROUP BY method_name, receiver_name, args_type_name, gem_name, gem_version " +
                           "HAVING COUNT(return_type_name) > 1;";
        final List<RSignature> signatures = executeQuery(sql);
        for (final RSignature signature : signatures) {
            final Set<String> returnTypeNames = getReturnTypeNamesBySignature(signature);
            final Set<ClassModuleSymbol> returnTypeSymbols = returnTypeNames.stream()
                    .map(returnTypeName -> SymbolUtil.findClassOrModule(project, returnTypeName))
                    .map(returnTypeSymbol -> (ClassModuleSymbol) returnTypeSymbol)
                    .collect(Collectors.toSet());
            String leastCommonSuperclassFQN = null;
            if (!returnTypeSymbols.contains(null)) {
                final ClassModuleSymbol leastCommonSuperclass = RSignatureDAG.getLeastCommonSuperclass(returnTypeSymbols);
                if (leastCommonSuperclass != null) {
                    leastCommonSuperclassFQN = String.join("::", leastCommonSuperclass.getFQN());
                }
            }

            signature.setReturnTypeName(StringUtil.notNullize(leastCommonSuperclassFQN, CoreTypes.Object));
            deleteSignature(signature);
            recordSignature(signature);
        }
    }

    private void mergeRecordsWithDifferentSignaturesButSameReturnTypeName(@NotNull final Project project) {
        String sql = "SELECT DISTINCT * FROM signatures GROUP BY method_name, receiver_name, gem_name, gem_version " +
                     "HAVING COUNT(args_type_name) > 1;";
        final List<RSignature> groups = executeQuery(sql);
        for (final RSignature signature : groups) {
            sql = String.format("SELECT * FROM signatures WHERE method_name = '%s' AND receiver_name = '%s' " +
                                "AND gem_name = '%s' AND gem_version = '%s';", signature.getMethodName(),
                                signature.getReceiverName(), signature.getGemName(), signature.getGemVersion());
            final List<RSignature> signatures = executeQuery(sql);

            final RSignatureDAG dag = new RSignatureDAG(project, signature.getArgsTypeName().size());
            dag.addAll(signatures);

            sql = String.format("DELETE FROM signatures WHERE method_name = '%s' AND receiver_name = '%s' " +
                                "AND gem_name = '%s' AND gem_version = '%s';", signature.getMethodName(),
                                signature.getReceiverName(), signature.getGemName(), signature.getGemVersion());
            executeUpdate(sql);

            dag.depthFirstSearch(this::recordSignature);
        }
    }

    @NotNull
    private Set<String> getReturnTypeNamesBySignature(@NotNull final RSignature signature) {
        final String sql = String.format("SELECT * FROM signatures " +
                                         "WHERE method_name = '%s' AND receiver_name = '%s' " +
                                         "AND args_type_name = '%s' AND gem_name = '%s' AND gem_version = '%s';",
                                         signature.getMethodName(), signature.getReceiverName(),
                                         String.join(";", signature.getArgsTypeName()),
                                         signature.getGemName(), signature.getGemVersion());
        final List<RSignature> signatures = executeQuery(sql);
        return signatures.stream()
                .map(RSignature::getReturnTypeName)
                .collect(Collectors.toSet());
    }

    private void executeUpdate(@NotNull final String sql) {
        try (final Statement statement = myConnection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            LOG.info(e);
        }
    }

    @NotNull
    private List<RSignature> executeQuery(@NotNull final String sql) {
        final List<RSignature> signatures = new ArrayList<>();
        try (final Statement statement = myConnection.createStatement()) {
            final ResultSet resultSet = statement.executeQuery(sql);
            while (resultSet.next()) {
                final RSignature signature = new RSignatureBuilder(resultSet.getString("method_name"))
                        .setReceiverName(resultSet.getString("receiver_name"))
                        .setVisibility(Visibility.valueOf(resultSet.getString("visibility")))
                        .setArgsInfo(parseArgsInfo(resultSet.getString("args_info")))
                        .setArgsTypeName(StringUtil.splitHonorQuotes(resultSet.getString("args_type_name"), ';'))
                        .setGemName(resultSet.getString("gem_name"))
                        .setGemVersion(resultSet.getString("gem_version"))
                        .setReturnTypeName(resultSet.getString("return_type_name"))
                        .build();
                signatures.add(signature);
            }
        } catch (SQLException | IllegalArgumentException e) {
            LOG.info(e);
            return ContainerUtilRt.emptyList();
        }

        return signatures;
    }
}