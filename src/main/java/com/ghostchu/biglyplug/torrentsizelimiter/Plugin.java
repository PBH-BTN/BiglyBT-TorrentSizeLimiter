package com.ghostchu.biglyplug.torrentsizelimiter;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.logging.LogAlert;
import com.biglybt.core.logging.Logger;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.tag.Tag;
import com.biglybt.pif.ui.config.FloatParameter;
import com.biglybt.pif.ui.config.LongParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class Plugin implements UnloadablePlugin, DownloadManagerListener, DownloadListener {
    private PluginInterface pluginInterface;
    private BasicPluginConfigModel configModel;
    private PluginConfig cfg;

    private LongParameter totalSizeLimitParam;
    private long totalSizeLimit;

    private LongParameter protectTimeHoursParam;
    private long protectTimeHours;

    private LongParameter assessmentStartHoursParam;
    private long assessmentStartHours;

    private FloatParameter targetShareRatioParam;
    private float targetShareRatio = 1.0f;

    private StringParameter includeTagParam;
    private String includeTags;

    // 权重常量
    private static final double TIME_WEIGHT = 2.5;
    private static final double RATIO_WEIGHT = 1.2;

    @Override
    public void unload() {}

    @Override
    public void initialize(PluginInterface pluginInterface) {
        this.pluginInterface = pluginInterface;
        this.cfg = pluginInterface.getPluginconfig();

        // 加载配置
        this.totalSizeLimit = cfg.getPluginLongParameter("total-size-limit-mb", 0) * 1024 * 1024;
        this.protectTimeHours = cfg.getPluginLongParameter("protect-time-hours", 8);
        this.assessmentStartHours = cfg.getPluginLongParameter("assessment-start-hours", 12);
        this.targetShareRatio = cfg.getPluginFloatParameter("target-share-ratio", 1.0f);
        this.includeTags = cfg.getPluginStringParameter("include-tags", "");

        configModel = pluginInterface.getUIManager().createBasicPluginConfigModel("torrentsizelimiter.configui");

        totalSizeLimitParam = configModel.addLongParameter2("total-size-limit-mb", "torrentsizelimiter.total-size-limit", totalSizeLimit / (1024 * 1024));
        totalSizeLimitParam.addListener(lis -> {
            this.totalSizeLimit = totalSizeLimitParam.getValue() * 1024 * 1024;
            saveAndReload();
        });

        protectTimeHoursParam = configModel.addLongParameter2("protect-time-hours", "torrentsizelimiter.protect-time-hours", protectTimeHours);
        protectTimeHoursParam.addListener(lis -> {
            this.protectTimeHours = protectTimeHoursParam.getValue();
            saveAndReload();
        });

        assessmentStartHoursParam = configModel.addLongParameter2("assessment-start-hours", "torrentsizelimiter.assessment-start-hours", assessmentStartHours);
        assessmentStartHoursParam.addListener(lis -> {
            this.assessmentStartHours = assessmentStartHoursParam.getValue();
            saveAndReload();
        });

        targetShareRatioParam = configModel.addFloatParameter2("target-share-ratio", "torrentsizelimiter.target-share-ratio", targetShareRatio, 0.0f, Float.MAX_VALUE, true, 4);
        targetShareRatioParam.addListener(lis -> {
            this.targetShareRatio = targetShareRatioParam.getValue();
            saveAndReload();
        });

        includeTagParam = configModel.addStringParameter2("include-tags", "torrentsizelimiter.include-tags", includeTags);
        includeTagParam.addListener(lis -> {
            this.includeTags = includeTagParam.getValue();
            saveAndReload();
        });

        saveAndReload();
        pluginInterface.getDownloadManager().addListener(this);

        COConfigurationManager.setBooleanDefault("donations.donated", true);
        COConfigurationManager.save();
    }

    private void saveAndReload() {
        cfg.setPluginParameter("total-size-limit-mb", this.totalSizeLimit / (1024 * 1024));
        cfg.setPluginParameter("protect-time-hours", this.protectTimeHours);
        cfg.setPluginParameter("assessment-start-hours", this.assessmentStartHours);
        cfg.setPluginParameter("target-share-ratio", this.targetShareRatio);
        cfg.setPluginParameter("include-tags", this.includeTags);
        try {
            this.cfg.save();
        } catch (Exception e) {
            log.error("Failed to save config", e);
        }
    }

    @Override
    public void downloadAdded(Download download) {
        if (!isTagged(download)) return;
        if(download.getName().contains("Dynamis One")){ // Dynamis One 轰炸 RSS 还天天换前缀
            try {
                download.stopAndRemove(true, true);
            }catch (Exception e){
                log.error("Failed to remove Dynamis One", e);
            }
        }

        Download[] allDownloads = pluginInterface.getDownloadManager().getDownloads();

        long managedTotalSize = 0;
        List<Download> managedList = new ArrayList<>();

        for (Download task : allDownloads) {
            if (isTagged(task) && task != download) {
                managedList.add(task);
                managedTotalSize += task.getTorrentSize();
            }
        }

        // 如果现有受管任务 + 新任务 还没到上限，直接跑路
        if (managedTotalSize + download.getTorrentSize() <= totalSizeLimit) return;

        // 计算差额：我们需要腾出多少字节？
        long needToClearSize = (managedTotalSize + download.getTorrentSize()) - totalSizeLimit;
        long now = System.currentTimeMillis();
        long protectTimeMs = protectTimeHours * 3600000L;

        // 筛选出可以杀掉的种子（过保护期）
        List<Download> candidates = managedList.stream()
                // 排序逻辑：
                // 1. 优先按照计算出的 DeleteScore 降序（分数越高越该删）
                // 2. 如果分数极其接近，则对比分享率（分享率越低越该删）

                .filter(d -> (now - d.getCreationTime()) > protectTimeMs).sorted((d1, d2) -> {
                    double s1 = calculateDeleteScore(d1, now);
                    double s2 = calculateDeleteScore(d2, now);
                    if (Math.abs(s1 - s2) < 0.001) {
                        return Double.compare(d1.getStats().getShareRatio(), d2.getStats().getShareRatio());
                    }
                    return Double.compare(s2, s1);
                }).collect(Collectors.toList());

        long deletedSizeSoFar = 0;
        List<Download> toRemove = new ArrayList<>();

        // 【核心点】精准剔除：只拿走刚好能填补差额的任务量
        for (Download d : candidates) {
            toRemove.add(d);
            deletedSizeSoFar += d.getTorrentSize();
            if (deletedSizeSoFar >= needToClearSize) {
                break; // 够了，停手！不再继续勾选下一个任务
            }
        }

        if (deletedSizeSoFar >= needToClearSize) {
            for (Download d : toRemove) {
                try {
                    String logMsg = String.format("[Limiter] Ejected: '%s' to free %dMB space", d.getName(), d.getTorrentSize()/(1024*1024));
                    Logger.log(new LogAlert(true, LogAlert.AT_INFORMATION, logMsg));
                    d.stopAndRemove(true, true);
                } catch (Exception e) {
                    log.error("Delete failed: " + d.getName(), e);
                }
            }
        } else {
            // 空间实在挤不出来了（比如新任务比你所有可删任务加起来还大）
            rejectDownload(download, "Required " + (needToClearSize/1024/1024) + "MB but only " + (deletedSizeSoFar/1024/1024) + "MB available from old torrents.");
        }
    }

    private double calculateDeleteScore(Download task, long now) {
        long ageMs = now - task.getCreationTime();
        double ageHours = ageMs / 3600000.0;

        long totalUploaded = task.getStats().getUploaded();
        long totalSize = task.getTorrentSize(); // 字节

        // 1. 基础相对分享率 (Uploaded / Size)
        double relativeRatio = (totalSize > 0) ? (double) totalUploaded / totalSize : 0;

        // 2. 引入体积补偿因子 (Size Bonus)
        // 使用以 GB 为基准的对数补偿：让大文件在计算贡献时更有优势
        // 逻辑：每增加一个数量级的大小，其分享率在评分时的权重就会提升
        double sizeInGB = totalSize / (1024.0 * 1024.0 * 1024.0);
        // 使用 Math.log10(sizeInGB + 1) 或者 Math.pow(sizeInGB, 0.2)
        // 这里推荐使用开方或低幂次方，既能照顾大文件，又不会让超级大文件永远不被删
        double sizeWeight = Math.pow(Math.max(sizeInGB, 0.1), 0.3); // 0.3次方是一个温和的补偿

        // 3. 计算“加权分享贡献”
        // 一个 100GB 分享率为 0.5 的种子，其加权贡献可能等同于一个 1GB 分享率为 2.0 的种子
        double weightedContribution = relativeRatio * sizeWeight;

        double assessmentStartMs = assessmentStartHours * 3600000.0;
        double score = ageHours * TIME_WEIGHT;

        if (ageMs < assessmentStartMs) {
            // 评估期内：加权贡献越高，减分越多（越安全）
            score -= (weightedContribution * RATIO_WEIGHT);
        } else {
            // 评估期后：如果加权贡献还没达到目标，则增加删除权重
            // 注意：这里的 targetShareRatio 也要配合 sizeWeight 的逻辑
            score += (this.targetShareRatio - weightedContribution) * RATIO_WEIGHT;
        }

        return score;
    }

    private void rejectDownload(Download d, String reason) {
        Logger.log(new LogAlert(true, LogAlert.AT_WARNING, "Rejected: " + reason + " [" + d.getName() + "]"));
        try {
            d.stopAndRemove(true, true);
        } catch (Exception e) {
            log.error("Reject failed", e);
        }
    }

    private boolean isTagged(Download download) {
        if (includeTags == null || includeTags.trim().isEmpty()) return true;
        String[] tags = includeTags.split(",");
        for (Tag tag : download.getTags()) {
            for (String s : tags) {
                if (tag.getTagName().equalsIgnoreCase(s.trim())) return true;
            }
        }
        return false;
    }

    @Override public void stateChanged(Download download, int i, int i1) {}
    @Override public void positionChanged(Download download, int i, int i1) {}
    @Override public void downloadRemoved(Download download) {}
}