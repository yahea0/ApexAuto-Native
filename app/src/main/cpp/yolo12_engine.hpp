#pragma once

#include <vector>
#include <string>
#include <onnxruntime_cxx_api.h>

struct DetectionResult {
    float x1;
    float y1;
    float x2;
    float y2;
    float confidence;
    int class_id;
};

class YOLOv12Engine {
public:
    YOLOv12Engine(const std::string& model_path, float conf_thresh = 0.45f, float iou_thresh = 0.50f);
    ~YOLOv12Engine();

    std::vector<DetectionResult> detect(const uint8_t* rgba_data, int width, int height);

private:
    Ort::Env env_;
    Ort::Session session_{nullptr};
    Ort::SessionOptions session_options_;
    Ort::MemoryInfo memory_info_{nullptr};

    std::vector<const char*> input_names_{"images"};
    std::vector<const char*> output_names_{"output0"};

    float conf_threshold_;
    float iou_threshold_;
    const int input_w_ = 640;
    const int input_h_ = 640;

    void preprocess(const uint8_t* rgba, int w, int h, std::vector<float>& output_tensor, float& scale, int& pad_x, int& pad_y);
    std::vector<DetectionResult> postprocess(const float* raw_output, const std::vector<int64_t>& shape, float scale, int pad_x, int pad_y);
};
