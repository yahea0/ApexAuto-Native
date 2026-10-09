#pragma once

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>

class ORBMatcher {
public:
    ORBMatcher(int max_features = 500);
    bool matchTemplate(const cv::Mat& scene_crop, const cv::Mat& template_roi, float match_ratio = 0.75f);

private:
    cv::Ptr<cv::ORB> detector_;
    cv::Ptr<cv::BFMatcher> matcher_;
};
