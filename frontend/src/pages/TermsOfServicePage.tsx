import { Link } from "react-router-dom";

export function TermsOfServicePage() {
  return (
    <div style={pageStyle}>
      <div style={containerStyle}>
        <Link to="/" style={backLinkStyle}>
          ← Back to Faceless Chronicle AI
        </Link>
        <h1 style={titleStyle}>Terms of Service</h1>
        <p style={metaStyle}>Last updated: May 5, 2026</p>

        <section style={sectionStyle}>
          <p>
            These Terms of Service (&quot;Terms&quot;) govern your access to and use of Faceless
            Chronicle AI (the &quot;Service&quot;) provided by Faceless Chronicle AI (&quot;we&quot;,
            &quot;our&quot;, or &quot;us&quot;). By creating an account or using the Service, you
            agree to be bound by these Terms. If you do not agree, do not use the Service.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>1. Eligibility and Accounts</h2>
          <p>
            You must be at least 13 years old (or the minimum age in your jurisdiction) and able
            to form a binding contract to use the Service. You are responsible for the accuracy of
            the information you provide, for keeping your credentials secure, and for all activity
            that occurs under your account.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>2. The Service</h2>
          <p>
            The Service generates scripts, voiceovers, images, and rendered video clips using
            artificial intelligence based on prompts and configuration you provide, and can
            publish those videos to third-party platforms (such as YouTube, Facebook, TikTok, and
            X) that you connect. We may add, change, or remove features at any time.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>3. Your Content</h2>
          <p>
            You retain ownership of the prompts, scripts, and other inputs you submit
            (&quot;Inputs&quot;) and, to the extent permitted by law, of the videos and other
            outputs generated for you (&quot;Outputs&quot;). You grant us a worldwide,
            royalty-free license to host, store, process, transmit, and display your Inputs and
            Outputs solely as needed to operate, secure, and improve the Service and to perform
            the actions you request (such as publishing to a connected platform).
          </p>
          <p>
            You represent and warrant that you have all necessary rights to your Inputs and that
            your Inputs and Outputs do not infringe any third-party rights or violate any law.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>4. Acceptable Use</h2>
          <p>You agree not to use the Service to:</p>
          <ul style={listStyle}>
            <li>
              Generate or distribute content that is illegal, defamatory, harassing, hateful,
              sexually explicit involving minors, or otherwise harmful;
            </li>
            <li>Impersonate any person or entity, or misrepresent your affiliation;</li>
            <li>
              Generate misleading deepfakes or synthetic media of real individuals without their
              consent;
            </li>
            <li>
              Violate the terms of any connected third-party platform (including YouTube&apos;s,
              Facebook&apos;s, TikTok&apos;s, or X&apos;s policies);
            </li>
            <li>
              Probe, scan, or test the vulnerability of the Service, or interfere with its normal
              operation;
            </li>
            <li>
              Attempt to extract source code, model weights, or training data from the Service or
              its underlying providers;
            </li>
            <li>Resell or sublicense the Service without our written permission.</li>
          </ul>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>5. Third-Party Services</h2>
          <p>
            The Service integrates with third-party platforms via OAuth. Your use of those
            platforms is governed by their own terms and privacy policies. You are responsible
            for any content you publish through those platforms via the Service. We are not
            liable for the availability, content, or behavior of third-party services.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>6. Fees</h2>
          <p>
            Some features may require a paid subscription or usage-based fees. Pricing, billing
            cycle, and payment terms will be presented to you before purchase. Fees are
            non-refundable except where required by law. We may change pricing on prospective
            renewals with reasonable advance notice.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>7. Intellectual Property</h2>
          <p>
            The Service, including its software, design, trademarks, and documentation, is owned
            by us or our licensors and is protected by intellectual property laws. Except for the
            limited rights expressly granted in these Terms, no rights are transferred to you.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>8. AI-Generated Content Disclaimer</h2>
          <p>
            Outputs are generated by machine-learning models and may be inaccurate, incomplete,
            offensive, or otherwise unsuitable. You are solely responsible for reviewing Outputs
            before publishing or relying on them. The Service does not provide professional advice
            of any kind.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>9. Termination</h2>
          <p>
            You may stop using the Service and delete your account at any time. We may suspend or
            terminate your access if you violate these Terms, create risk for us or other users,
            or if we are required to do so by law. Upon termination, your right to use the
            Service ends immediately. Sections that by their nature should survive termination
            (including ownership, disclaimers, indemnity, and limitation of liability) will
            survive.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>10. Disclaimers</h2>
          <p>
            THE SERVICE IS PROVIDED &quot;AS IS&quot; AND &quot;AS AVAILABLE&quot; WITHOUT
            WARRANTIES OF ANY KIND, WHETHER EXPRESS, IMPLIED, OR STATUTORY, INCLUDING WITHOUT
            LIMITATION WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE,
            NON-INFRINGEMENT, AND ACCURACY. WE DO NOT WARRANT THAT THE SERVICE WILL BE
            UNINTERRUPTED, ERROR-FREE, OR SECURE.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>11. Limitation of Liability</h2>
          <p>
            TO THE MAXIMUM EXTENT PERMITTED BY LAW, IN NO EVENT WILL WE BE LIABLE FOR ANY
            INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, OR ANY LOSS OF
            PROFITS, REVENUE, DATA, OR GOODWILL, ARISING OUT OF OR IN CONNECTION WITH THESE TERMS
            OR THE SERVICE. OUR TOTAL CUMULATIVE LIABILITY WILL NOT EXCEED THE GREATER OF (A) THE
            AMOUNT YOU PAID US IN THE TWELVE MONTHS PRIOR TO THE EVENT GIVING RISE TO LIABILITY,
            OR (B) USD $100.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>12. Indemnification</h2>
          <p>
            You agree to defend, indemnify, and hold us harmless from any claims, damages,
            liabilities, and expenses (including reasonable attorneys&apos; fees) arising out of
            your Inputs, your Outputs, your use of the Service, or your violation of these Terms.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>13. Changes to These Terms</h2>
          <p>
            We may update these Terms from time to time. If a change is material, we will give you
            reasonable notice (for example, by posting in the application or by email). Your
            continued use of the Service after the effective date constitutes acceptance of the
            updated Terms.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>14. Governing Law and Disputes</h2>
          <p>
            These Terms are governed by the laws of the jurisdiction in which we are
            headquartered, without regard to conflict-of-laws principles. The courts located in
            that jurisdiction will have exclusive jurisdiction over any disputes arising out of or
            relating to these Terms or the Service, except that either party may seek injunctive
            relief in any competent court.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>15. Contact</h2>
          <p>
            Questions about these Terms? Email us at{" "}
            <a href="mailto:support@faceless-chronicle.com">support@faceless-chronicle.com</a>.
          </p>
        </section>

        <footer style={footerStyle}>
          <Link to="/tos">Terms of Service</Link>
          <span style={dotStyle}>•</span>
          <Link to="/privacy-policy">Privacy Policy</Link>
          <span style={dotStyle}>•</span>
          <Link to="/delete-data">Data Deletion</Link>
        </footer>
      </div>
    </div>
  );
}

const pageStyle: React.CSSProperties = {
  minHeight: "100vh",
  background: "#0e0f12",
  color: "#e6e6e6",
  padding: "48px 20px",
};

const containerStyle: React.CSSProperties = {
  maxWidth: 760,
  margin: "0 auto",
  lineHeight: 1.6,
  fontSize: 15,
};

const backLinkStyle: React.CSSProperties = {
  display: "inline-block",
  marginBottom: 24,
  color: "#60a5fa",
  textDecoration: "none",
  fontSize: 14,
};

const titleStyle: React.CSSProperties = {
  fontSize: 32,
  marginTop: 0,
  marginBottom: 8,
};

const metaStyle: React.CSSProperties = {
  color: "#888",
  marginTop: 0,
  marginBottom: 32,
  fontSize: 13,
};

const sectionStyle: React.CSSProperties = {
  marginBottom: 28,
};

const h2Style: React.CSSProperties = {
  fontSize: 20,
  marginTop: 0,
  marginBottom: 12,
  borderBottom: "1px solid #1f2125",
  paddingBottom: 6,
};

const listStyle: React.CSSProperties = {
  paddingLeft: 22,
  margin: 0,
};

const footerStyle: React.CSSProperties = {
  marginTop: 48,
  paddingTop: 20,
  borderTop: "1px solid #1f2125",
  fontSize: 13,
  color: "#888",
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
};

const dotStyle: React.CSSProperties = {
  color: "#444",
};