import { Link } from "react-router-dom";

export function PrivacyPolicyPage() {
  return (
    <div style={pageStyle}>
      <div style={containerStyle}>
        <Link to="/" style={backLinkStyle}>
          ← Back to Faceless Chronicle AI
        </Link>
        <h1 style={titleStyle}>Privacy Policy</h1>
        <p style={metaStyle}>Last updated: May 5, 2026</p>

        <section style={sectionStyle}>
          <p>
            Faceless Chronicle AI (&quot;we&quot;, &quot;our&quot;, or &quot;us&quot;) operates the Faceless
            Chronicle AI application and related services (the &quot;Service&quot;). This Privacy Policy
            explains what information we collect, how we use it, and the choices you have. By using
            the Service you agree to the collection and use of information in accordance with this
            policy.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>1. Information We Collect</h2>
          <h3 style={h3Style}>Account information</h3>
          <p>
            When you sign in with Google or Facebook, we receive your name, email address, and
            profile picture. We use this information to create and identify your account and to
            personalize the Service.
          </p>
          <h3 style={h3Style}>Connected social accounts</h3>
          <p>
            When you connect YouTube, Facebook, TikTok, or X (Twitter) to publish videos, we store
            the OAuth access and refresh tokens issued by those platforms, along with the
            associated channel, page, or account identifiers. We use these tokens solely to
            upload, schedule, and manage the videos you ask us to publish on your behalf.
          </p>
          <h3 style={h3Style}>Content you create</h3>
          <p>
            We store the prompts, scripts, audio narration, generated images, and rendered videos
            produced by the Service so that you can review, edit, and publish them. These artifacts
            are associated with your account.
          </p>
          <h3 style={h3Style}>Usage and technical data</h3>
          <p>
            We collect log data such as IP address, browser type, request paths, timestamps, and
            error traces. This data is used to operate, secure, and improve the Service.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>2. How We Use Your Information</h2>
          <ul style={listStyle}>
            <li>To authenticate you and maintain your session.</li>
            <li>To generate scripts, voiceovers, images, and rendered videos at your request.</li>
            <li>
              To publish content to the third-party platforms (YouTube, Facebook, TikTok, X) you
              have explicitly connected.
            </li>
            <li>To monitor reliability, detect abuse, and improve the Service.</li>
            <li>To respond to your support requests and account inquiries.</li>
          </ul>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>3. How We Share Your Information</h2>
          <p>
            We do not sell your personal information. We share information only with:
          </p>
          <ul style={listStyle}>
            <li>
              <strong>Third-party platforms you connect</strong> — YouTube/Google, Facebook/Meta,
              TikTok, and X (Twitter), in order to perform the actions you authorize (such as
              uploading a video).
            </li>
            <li>
              <strong>AI and infrastructure providers</strong> — services we use to generate text,
              speech, and imagery, and to host the application. These providers process data on
              our behalf under contractual safeguards.
            </li>
            <li>
              <strong>Legal authorities</strong> — when required to comply with applicable law,
              valid legal process, or to protect the rights, safety, and property of users or the
              public.
            </li>
          </ul>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>4. Use of Google API Data</h2>
          <p>
            Faceless Chronicle AI&apos;s use and transfer of information received from Google APIs
            adheres to the{" "}
            <a
              href="https://developers.google.com/terms/api-services-user-data-policy"
              target="_blank"
              rel="noreferrer"
            >
              Google API Services User Data Policy
            </a>
            , including the Limited Use requirements. We only request the YouTube and profile
            scopes needed to upload and manage videos you choose to publish. We do not use Google
            user data to train generalized AI/ML models.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>5. Data Retention</h2>
          <p>
            We retain account data, generated content, and connection tokens for as long as your
            account is active. You can disconnect a social platform at any time from the
            Connections page, which revokes the stored tokens. You can request deletion of your
            account and associated data at any time — see the{" "}
            <Link to="/delete-data">Data Deletion</Link> page for instructions. Backups are purged
            on a rolling 30-day cycle.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>6. Security</h2>
          <p>
            We use industry-standard safeguards including TLS in transit, encryption of OAuth
            tokens at rest, role-based access controls, and audit logging. No system is perfectly
            secure; if you discover a vulnerability, please report it to the contact address below.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>7. Your Rights</h2>
          <p>
            Depending on where you live, you may have the right to access, correct, export, or
            delete your personal information, and to object to or restrict certain processing. To
            exercise these rights, contact us at the address below or use the{" "}
            <Link to="/delete-data">Data Deletion</Link> page.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>8. Children&apos;s Privacy</h2>
          <p>
            The Service is not directed to children under 13 (or the equivalent minimum age in
            your jurisdiction). We do not knowingly collect personal information from children. If
            you believe a child has provided us with personal information, please contact us so we
            can delete it.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>9. International Transfers</h2>
          <p>
            Your information may be processed in countries other than your own. Where required, we
            rely on appropriate safeguards such as standard contractual clauses to protect your
            data.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>10. Changes to This Policy</h2>
          <p>
            We may update this Privacy Policy from time to time. We will post the updated policy
            here with a new &quot;Last updated&quot; date and, where appropriate, notify you in the
            application.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>11. Contact Us</h2>
          <p>
            If you have any questions about this Privacy Policy, please contact us at{" "}
            <a href="mailto:privacy@facelesschronicle.ai">privacy@facelesschronicle.ai</a>.
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

const h3Style: React.CSSProperties = {
  fontSize: 16,
  marginTop: 16,
  marginBottom: 6,
  color: "#cfcfcf",
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