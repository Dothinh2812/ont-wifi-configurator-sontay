import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public struct FooterSignatureBar: View {
    public static let signatureText = "Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"
    public static let supportPhone = "0946827186"

    @State private var copiedToast = false

    public init() {}

    public var body: some View {
        HStack(spacing: 8) {
            Button {
                copyPhoneNumber()
            } label: {
                Text(copiedToast ? "Đã sao chép SĐT hỗ trợ: \(Self.supportPhone)" : Self.signatureText)
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(.primary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)

            Button {
                copyPhoneNumber()
            } label: {
                Image(systemName: "doc.on.doc")
                    .font(.subheadline)
                    .foregroundColor(Color(red: 0.0, green: 91.0 / 255.0, blue: 170.0 / 255.0))
                    .padding(6)
            }
            .buttonStyle(.plain)

            Button {
                dialPhoneNumber()
            } label: {
                Image(systemName: "phone.fill")
                    .font(.subheadline)
                    .foregroundColor(Color(red: 0.0, green: 91.0 / 255.0, blue: 170.0 / 255.0))
                    .padding(6)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 12)
        .background(Color(UIColor.secondarySystemBackground))
        .overlay(
            Rectangle()
                .frame(height: 1)
                .foregroundColor(Color.gray.opacity(0.25)),
            alignment: .top
        )
    }

    private func copyPhoneNumber() {
        #if canImport(UIKit)
        UIPasteboard.general.string = Self.supportPhone
        #endif
        copiedToast = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            copiedToast = false
        }
    }

    private func dialPhoneNumber() {
        #if canImport(UIKit)
        if let url = URL(string: "tel://\(Self.supportPhone)") {
            UIApplication.shared.open(url)
        }
        #endif
    }
}
