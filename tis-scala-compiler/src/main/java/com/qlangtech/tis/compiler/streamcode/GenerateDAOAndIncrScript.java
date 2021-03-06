/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 *
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.compiler.streamcode;

import com.alibaba.citrus.turbine.Context;
import com.google.common.collect.Lists;
import com.koubei.abator.KoubeiIbatorRunner;
import com.koubei.abator.KoubeiProgressCallback;
import com.qlangtech.tis.compiler.java.JavaCompilerProcess;
import com.qlangtech.tis.compiler.java.MyJavaFileManager;
import com.qlangtech.tis.compiler.java.MyJavaFileObject;
import com.qlangtech.tis.compiler.java.NestClassFileObject;
import com.qlangtech.tis.coredefine.module.action.IbatorProperties;
import com.qlangtech.tis.coredefine.module.action.IndexIncrStatus;
import com.qlangtech.tis.db.parser.domain.DBConfig;
import com.qlangtech.tis.git.GitUtils;
import com.qlangtech.tis.manage.common.Config;
import com.qlangtech.tis.manage.common.incr.StreamContextConstant;
import com.qlangtech.tis.offline.DbScope;
import com.qlangtech.tis.runtime.module.misc.IControlMsgHandler;
import com.qlangtech.tis.sql.parser.DBNode;
import com.qlangtech.tis.sql.parser.stream.generate.FacadeContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.ibator.config.IbatorContext;
import scala.tools.ScalaCompilerSupport;
import scala.tools.scala_maven_executions.LogProcessorUtils;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/04/13
 */
public class GenerateDAOAndIncrScript {

    private final IControlMsgHandler msgHandler;

    private final IndexStreamCodeGenerator indexStreamCodeGenerator;

    public GenerateDAOAndIncrScript(IControlMsgHandler msgHandler, IndexStreamCodeGenerator indexStreamCodeGenerator) {
        this.msgHandler = msgHandler;
        this.indexStreamCodeGenerator = indexStreamCodeGenerator;
    }

    /**
     * @param incrStatus
     * @param compilerAndPackage
     * @throws Exception
     */
    public void generate(Context context, IndexIncrStatus incrStatus, boolean compilerAndPackage, Map<Integer, /**
     * DBID
     */
    Long> dependencyDbs) throws Exception {
        final Map<DBNode, List<String>> dbNameMap = indexStreamCodeGenerator.getDbTables();
        if (dbNameMap.size() < 1) {
            throw new IllegalStateException("dbNameMap size can not small than 1");
        }
        DBConfig dbConfig = null;
        if (dbNameMap.size() != dependencyDbs.size()) {
            throw new IllegalStateException("dbNameMap.size() " + dbNameMap.size() + " != dependencyDbs.size()" + dependencyDbs.size());
        }
        // long timestampp;// = Long.parseLong(ManageUtils.formatNowYyyyMMddHHmmss());
        final KoubeiProgressCallback koubeiProgressCallback = new KoubeiProgressCallback();
        List<IbatorContext> daoFacadeList = Lists.newArrayList();
        Long lastOptime = null;
        for (Map.Entry<DBNode, List<String>> /* dbname */
        entry : dbNameMap.entrySet()) {
            lastOptime = dependencyDbs.get(entry.getKey().getDbId());
            if (lastOptime == null) {
                throw new IllegalStateException("db " + entry.getKey() + " is not find in dependency dbs:" + dbNameMap.keySet().stream().map((r) -> "[" + r.getDbId() + ":" + r.getDbName() + "]").collect(Collectors.joining(",")));
            }
            // ManageUtils.formatNowYyyyMMddHHmmss(lastOptime);
            long timestamp = lastOptime;
            // 
            dbConfig = GitUtils.$().getDbLinkMetaData(entry.getKey().getDbName(), DbScope.DETAILED);
            IbatorProperties properties = new IbatorProperties(dbConfig, entry.getValue(), timestamp);
            // entry.getKey().setDaoDir(properties.getDaoDir());
            entry.getKey().setTimestampVer(timestamp);
            if (entry.getValue().size() < 1) {
                throw new IllegalStateException("db:" + entry.getKey() + " relevant tablesList can not small than 1");
            }
            KoubeiIbatorRunner runner = new KoubeiIbatorRunner(properties) {

                @Override
                protected KoubeiProgressCallback getProgressCallback() {
                    return koubeiProgressCallback;
                }
            };
            IbatorContext ibatorContext = runner.getIbatorContext();
            daoFacadeList.add(ibatorContext);
            try {
                if (!properties.isDaoScriptCreated()) {
                    // 生成源代码
                    runner.build();
                    // dao script 脚本已经创建不需要再创建了
                    // if (compilerAndPackage) {
                    // 直接生成就行了，别管当前是不是要编译了
                    File classpathDir = new File(Config.getDataDir(), "libs/tis-ibatis");
                    // File classpathDir = new File("/Users/mozhenghua/Desktop/j2ee_solution/project/tis-ibatis/target/dependency");
                    JavaCompilerProcess daoCompilerPackageProcess = new JavaCompilerProcess(dbConfig, properties.getDaoDir(), classpathDir);
                    // 打包,生成jar包
                    daoCompilerPackageProcess.compileAndBuildJar();
                // }
                }
            } catch (Exception e) {
                // 将文件夹清空
                FileUtils.forceDelete(properties.getDaoDir());
                throw new RuntimeException("dao path:" + properties.getDaoDir(), e);
            }
        }
        if (daoFacadeList.size() < 1) {
            throw new IllegalStateException("daoFacadeList can not small than 1");
        }
        daoFacadeList.stream().forEach((r) -> {
            FacadeContext fc = new FacadeContext();
            fc.setFacadeInstanceName(r.getFacadeInstanceName());
            fc.setFullFacadeClassName(r.getFacadeFullClassName());
            fc.setFacadeInterfaceName(r.getFacadeInterface());
            indexStreamCodeGenerator.facadeList.add(fc);
        });
        try {
            File sourceRoot = StreamContextConstant.getStreamScriptRootDir(indexStreamCodeGenerator.collection, indexStreamCodeGenerator.incrScriptTimestamp);
            if (!indexStreamCodeGenerator.isIncrScriptDirCreated() || // 检查Faild Token文件是否存在
            ScalaCompilerSupport.incrStreamCodeCompileFaild(sourceRoot)) {
                /**
                 * *********************************************************************************
                 * 自动生成scala代码
                 * ***********************************************************************************
                 */
                indexStreamCodeGenerator.generateStreamScriptCode();
                // 生成依赖dao依赖元数据信息
                DBNode.dump(dbNameMap.keySet().stream().collect(Collectors.toList()), StreamContextConstant.getDbDependencyConfigMetaFile(indexStreamCodeGenerator.collection, indexStreamCodeGenerator.incrScriptTimestamp));
                /**
                 * *********************************************************************************
                 * 生成spring相关配置文件
                 * ***********************************************************************************
                 */
                indexStreamCodeGenerator.generateConfigFiles();
            }
            incrStatus.setIncrScriptMainFileContent(indexStreamCodeGenerator.readIncrScriptMainFileContent());
            // TODO 真实生产环境中需要 和 代码build阶段分成两步
            if (compilerAndPackage) {
                /**
                 * *********************************************************************************
                 * 编译增量脚本
                 * ***********************************************************************************
                 */
                if (this.streamScriptCompile(sourceRoot, dbNameMap.keySet())) {
                    this.msgHandler.addErrorMessage(context, "增量脚本编译失败");
                    this.msgHandler.addFieldError(context, "incr_script_compile_error", "error");
                    return;
                }
                /**
                 * *********************************************************************************
                 * 对scala代码进行 打包
                 * ***********************************************************************************
                 */
                JavaCompilerProcess.SourceGetterStrategy getterStrategy = new JavaCompilerProcess.SourceGetterStrategy(false, "/src/main/scala", ".scala") {

                    @Override
                    public JavaFileObject.Kind getSourceKind() {
                        // 没有scala的类型，暂且用other替换一下
                        return JavaFileObject.Kind.OTHER;
                    }

                    @Override
                    public MyJavaFileObject processMyJavaFileObject(MyJavaFileObject fileObj) {
                        try {
                            try (InputStream input = FileUtils.openInputStream(fileObj.getSourceFile())) {
                                IOUtils.copy(input, fileObj.openOutputStream());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return fileObj;
                    }
                };
                // 
                JavaCompilerProcess.FileObjectsContext fileObjects = JavaCompilerProcess.getFileObjects(sourceRoot, getterStrategy);
                final JavaCompilerProcess.FileObjectsContext compiledCodeContext = new JavaCompilerProcess.FileObjectsContext();
                File streamScriptClassesDir = new File(sourceRoot, "classes");
                appendClassFile(streamScriptClassesDir, compiledCodeContext, null);
                // 取得spring配置文件相关resourece
                JavaCompilerProcess.FileObjectsContext xmlConfigs = indexStreamCodeGenerator.getSpringXmlConfigsObjectsContext();
                // 将stream code打包
                // indexStreamCodeGenerator.getAppDomain().getAppName() + "-incr.jar"
                JavaCompilerProcess.packageJar(// indexStreamCodeGenerator.getAppDomain().getAppName() + "-incr.jar"
                sourceRoot, StreamContextConstant.getIncrStreamJarName(indexStreamCodeGenerator.collection), fileObjects, compiledCodeContext, xmlConfigs);
            }
        } catch (Exception e) {
            // 将原始文件删除干净
            try {
                FileUtils.forceDelete(indexStreamCodeGenerator.getStreamCodeGenerator().getIncrScriptDir());
            } catch (Throwable ex) {
            // ex.printStackTrace();
            }
            throw new RuntimeException(e);
        }
    }

    private void appendClassFile(File parent, JavaCompilerProcess.FileObjectsContext fileObjects, final StringBuffer qualifiedClassName) throws IOException {
        String[] children = parent.list();
        File childFile = null;
        for (String child : children) {
            childFile = new File(parent, child);
            if (childFile.isDirectory()) {
                StringBuffer newQualifiedClassName = null;
                if (qualifiedClassName == null) {
                    newQualifiedClassName = new StringBuffer(child);
                } else {
                    newQualifiedClassName = (new StringBuffer(qualifiedClassName)).append(".").append(child);
                }
                appendClassFile(childFile, fileObjects, newQualifiedClassName);
            } else {
                final String className = StringUtils.substringBeforeLast(child, ".");
                // 
                NestClassFileObject fileObj = MyJavaFileManager.getNestClassFileObject(((new StringBuffer(qualifiedClassName)).append(".").append(className)).toString(), fileObjects.classMap);
                try (InputStream input = FileUtils.openInputStream(childFile)) {
                    IOUtils.copy(input, fileObj.openOutputStream());
                }
            }
        }
    }

    private boolean streamScriptCompile(File sourceRoot, Set<DBNode> dependencyDBNodes) throws Exception {
        LogProcessorUtils.LoggerListener loggerListener = new LogProcessorUtils.LoggerListener() {

            @Override
            public void receiveLog(LogProcessorUtils.Level level, String line) {
                System.err.println(line);
            }
        };
        return ScalaCompilerSupport.streamScriptCompile(sourceRoot, DBNode.appendDBDependenciesClasspath(dependencyDBNodes), loggerListener);
    }
}
