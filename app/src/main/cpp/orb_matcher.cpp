#include "orb_matcher.hpp"

ORBMatcher::ORBMatcher(int max_features) {
    detector_ = cv::ORB::create(max_features);
    matcher_ = cv::BFMatcher::create(cv::NORM_HAMMING, false);
}

bool ORBMatcher::matchTemplate(const cv::Mat& scene_crop, const cv::Mat& template_roi, float match_ratio) {
    if (scene_crop.empty() || template_roi.empty()) return false;

    std::vector<cv::KeyPoint> kp_scene, kp_template;
    cv::Mat desc_scene, desc_template;

    detector_->detectAndCompute(scene_crop, cv::noArray(), kp_scene, desc_scene);
    detector_->detectAndCompute(template_roi, cv::noArray(), kp_template, desc_template);

    if (desc_scene.empty() || desc_template.empty()) return false;

    std::vector<std::vector<cv::DMatch>> knn_matches;
    matcher_->knnMatch(desc_template, desc_scene, knn_matches, 2);

    int good_matches = 0;
    for (const auto& match : knn_matches) {
        if (match.size() >= 2 && match[0].distance < match_ratio * match[1].distance) {
            good_matches++;
        }
    }

    return good_matches > 15;
}
