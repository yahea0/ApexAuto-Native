#include "yolo12_engine.hpp"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "Apex_YOLOv12"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

YOLOv12Engine::YOLOv12Engine(const std::string& model_path, float conf_thresh, float iou_thresh)
    : env_(ORT_LOGGING_LEVEL_WARNING, "ApexYOLOv12"),
      conf_threshold_(conf_thresh),
      iou_threshold_(iou_thresh) {
    
    session_options_.SetIntraOpNumThreads(4);
    session_options_.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    session_ = Ort::Session(env_, model_path.c_str(), session_options_);
    memory_info_ = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    LOGI("YOLOv12-N Model Loaded Successfully.");
}

YOLOv12Engine::~YOLOv12Engine() = default;

void YOLOv12Engine::preprocess(const uint8_t* rgba, int w, int h, std::vector<float>& output_tensor, float& scale, int& pad_x, int& pad_y) {
    scale = std::min(static_cast<float>(input_w_) / w, static_cast<float>(input_h_) / h);
    int new_unpad_w = static_cast<int>(w * scale);
    int new_unpad_h = static_cast<int>(h * scale);
    pad_x = (input_w_ - new_unpad_w) / 2;
    pad_y = (input_h_ - new_unpad_h) / 2;

    output_tensor.assign(1 * 3 * input_h_ * input_w_, 0.5f);

    int channel_size = input_w_ * input_h_;
    for (int y = 0; y < new_unpad_h; ++y) {
        int src_y = static_cast<int>(y / scale);
        for (int x = 0; x < new_unpad_w; ++x) {
            int src_x = static_cast<int>(x / scale);
            int src_idx = (src_y * w + src_x) * 4;
            int dst_idx = (y + pad_y) * input_w_ + (x + pad_x);

            output_tensor[0 * channel_size + dst_idx] = rgba[src_idx + 0] / 255.0f;
            output_tensor[1 * channel_size + dst_idx] = rgba[src_idx + 1] / 255.0f;
            output_tensor[2 * channel_size + dst_idx] = rgba[src_idx + 2] / 255.0f;
        }
    }
}

std::vector<DetectionResult> YOLOv12Engine::detect(const uint8_t* rgba_data, int width, int height) {
    std::vector<float> input_tensor_values;
    float scale = 1.0f;
    int pad_x = 0, pad_y = 0;
    
    preprocess(rgba_data, width, height, input_tensor_values, scale, pad_x, pad_y);

    std::vector<int64_t> input_shape = {1, 3, input_h_, input_w_};
    Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
        memory_info_, input_tensor_values.data(), input_tensor_values.size(),
        input_shape.data(), input_shape.size());

    auto output_tensors = session_.Run(
        Ort::RunOptions{nullptr},
        input_names_.data(), &input_tensor, 1,
        output_names_.data(), output_names_.size()
    );

    float* raw_output = output_tensors[0].GetTensorMutableData<float>();
    auto shape = output_tensors[0].GetTensorTypeAndShapeInfo().GetShape();

    return postprocess(raw_output, shape, scale, pad_x, pad_y);
}

std::vector<DetectionResult> YOLOv12Engine::postprocess(const float* raw_output, const std::vector<int64_t>& shape, float scale, int pad_x, int pad_y) {
    int dimensions = static_cast<int>(shape[1]);
    int proposals = static_cast<int>(shape[2]);
    int num_classes = dimensions - 4;

    std::vector<DetectionResult> candidates;

    for (int i = 0; i < proposals; ++i) {
        float max_class_score = 0.0f;
        int best_class_id = -1;

        for (int c = 0; c < num_classes; ++c) {
            float score = raw_output[(4 + c) * proposals + i];
            if (score > max_class_score) {
                max_class_score = score;
                best_class_id = c;
            }
        }

        if (max_class_score >= conf_threshold_) {
            float cx = raw_output[0 * proposals + i];
            float cy = raw_output[1 * proposals + i];
            float w  = raw_output[2 * proposals + i];
            float h  = raw_output[3 * proposals + i];

            float x1 = (cx - w / 2.0f - pad_x) / scale;
            float y1 = (cy - h / 2.0f - pad_y) / scale;
            float x2 = (cx + w / 2.0f - pad_x) / scale;
            float y2 = (cy + h / 2.0f - pad_y) / scale;

            candidates.push_back({x1, y1, x2, y2, max_class_score, best_class_id});
        }
    }

    std::sort(candidates.begin(), candidates.end(), [](const DetectionResult& a, const DetectionResult& b) {
        return a.confidence > b.confidence;
    });

    std::vector<DetectionResult> final_results;
    std::vector<bool> suppressed(candidates.size(), false);

    for (size_t i = 0; i < candidates.size(); ++i) {
        if (suppressed[i]) continue;
        final_results.push_back(candidates[i]);

        for (size_t j = i + 1; j < candidates.size(); ++j) {
            if (suppressed[j]) continue;

            float inter_x1 = std::max(candidates[i].x1, candidates[j].x1);
            float inter_y1 = std::max(candidates[i].y1, candidates[j].y1);
            float inter_x2 = std::min(candidates[i].x2, candidates[j].x2);
            float inter_y2 = std::min(candidates[i].y2, candidates[j].y2);

            float inter_w = std::max(0.0f, inter_x2 - inter_x1);
            float inter_h = std::max(0.0f, inter_y2 - inter_y1);
            float inter_area = inter_w * inter_h;

            float area_i = (candidates[i].x2 - candidates[i].x1) * (candidates[i].y2 - candidates[i].y1);
            float area_j = (candidates[j].x2 - candidates[j].x1) * (candidates[j].y2 - candidates[j].y1);
            float union_area = area_i + area_j - inter_area;

            float iou = (union_area > 0.0f) ? (inter_area / union_area) : 0.0f;
            if (iou > iou_threshold_) {
                suppressed[j] = true;
            }
        }
    }

    return final_results;
}
