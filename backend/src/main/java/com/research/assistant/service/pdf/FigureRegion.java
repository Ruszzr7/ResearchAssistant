package com.research.assistant.service.pdf;

/**
 * PDF 图表区域。
 *
 * @param page       页码（从 1 开始）
 * @param x          区域左上角 x 坐标
 * @param y          区域左上角 y 坐标
 * @param width      区域宽度
 * @param height     区域高度
 * @param caption    图表标题/说明（可能为空）
 * @param imagePath  提取的图片保存路径（可能为空）
 * @param type       区域类型（FIGURE / TABLE）
 */
public record FigureRegion(int page, float x, float y, float width, float height,
                           String caption, String imagePath, FigureRegionType type) {

    public FigureRegion {
        if (type == null) {
            type = FigureRegionType.FIGURE;
        }
    }
}
