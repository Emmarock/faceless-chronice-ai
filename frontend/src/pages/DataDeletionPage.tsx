import { Link } from "react-router-dom";

export function DataDeletionPage() {
  return (
    <div style={pageStyle}>
      <div style={containerStyle}>
        <Link to="/" style={backLinkStyle}>
          ← Back to Faceless Chronicle AI
        </Link>
        <h1 style={titleStyle}>Data Deletion Instructions</h1>
        <p style={metaStyle}>Last updated: May 5, 2026</p>

        <section style={sectionStyle}>
          <p>
            Faceless Chronicle AI respects your right to control your personal data. This page
            explains what data we hold about you, how to disconnect a third-party account, and
            how to permanently delete your account and all associated data.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>What data we hold about you</h2>
          <ul style={listStyle}>
            <li>
              <strong>Profile</strong> — your name, email, and avatar from your Google or Facebook
              login.
            </li>
            <li>
              <strong>Connected social accounts</strong> — OAuth access and refresh tokens for the
              YouTube, Facebook, TikTok, or X (Twitter) accounts you have linked, plus the
              channel/page identifiers needed to publish on your behalf.
            </li>
            <li>
              <strong>Generated content</strong> — prompts, scripts, voiceovers, generated images,
              and rendered video files associated with the jobs you have created.
            </li>
            <li>
              <strong>Activity and audit logs</strong> — non-sensitive records used to operate and
              secure the Service.
            </li>
          </ul>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>Option 1 — Disconnect a single platform</h2>
          <p>
            If you only want to remove a connection to YouTube, Facebook, TikTok, or X, you do
            <em> not </em> need to delete your account.
          </p>
          <ol style={listStyle}>
            <li>
              Sign in and open the <Link to="/connections">Connections</Link> page.
            </li>
            <li>
              Click <strong>Disconnect</strong> next to the platform you want to remove.
            </li>
            <li>
              We immediately revoke and delete the OAuth tokens and platform identifiers for that
              connection.
            </li>
          </ol>
          <p>
            For Facebook specifically, you can also remove our app at{" "}
            <a
              href="https://www.facebook.com/settings?tab=business_tools"
              target="_blank"
              rel="noreferrer"
            >
              Facebook Settings → Business Integrations
            </a>
            . For Google/YouTube, you can revoke access at{" "}
            <a
              href="https://myaccount.google.com/permissions"
              target="_blank"
              rel="noreferrer"
            >
              Google Account → Third-party access
            </a>
            .
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>Option 2 — Delete your account and all data</h2>
          <p>To permanently delete your account and all associated data:</p>
          <ol style={listStyle}>
            <li>
              Send an email to{" "}
              <a href="mailto:privacy@facelesschronicle.ai?subject=Account%20Deletion%20Request">
                privacy@facelesschronicle.ai
              </a>{" "}
              from the email address associated with your account.
            </li>
            <li>
              Use the subject line <code style={codeStyle}>Account Deletion Request</code>.
            </li>
            <li>
              In the body, confirm that you want your account and all associated data deleted.
            </li>
          </ol>
          <p>
            We will verify ownership of the account, then within <strong>30 days</strong>:
          </p>
          <ul style={listStyle}>
            <li>Delete your profile and authentication records.</li>
            <li>Revoke and delete every OAuth token connected to your account.</li>
            <li>
              Delete all jobs, scripts, audio, images, and rendered videos generated through your
              account.
            </li>
            <li>
              Purge associated rows from active databases. Encrypted backups are rotated on a
              30-day cycle and will no longer contain your data after that window.
            </li>
          </ul>
          <p>
            We will send a confirmation email once deletion is complete. Some records may be
            retained where law requires (for example, financial records for tax purposes), in
            which case we will tell you what was retained and why.
          </p>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>Facebook Data Deletion Callback</h2>
          <p>
            If you signed in with Facebook and want to remove your data, you can also trigger
            deletion from Facebook itself:
          </p>
          <ol style={listStyle}>
            <li>
              Go to your Facebook account &gt; Settings &amp; Privacy &gt; Settings &gt;
              Apps and Websites.
            </li>
            <li>Locate Faceless Chronicle AI and click <strong>Remove</strong>.</li>
            <li>
              Facebook will notify us with a deletion request, and we will process it within 30
              days. Your deletion confirmation code and status URL will be returned through
              Facebook&apos;s standard deletion-status flow.
            </li>
          </ol>
        </section>

        <section style={sectionStyle}>
          <h2 style={h2Style}>Questions</h2>
          <p>
            For any questions about deletion or data access, email us at{" "}
            <a href="mailto:privacy@facelesschronicle.ai">privacy@facelesschronicle.ai</a>. See
            also our <Link to="/privacy-policy">Privacy Policy</Link> for details on what we
            collect and why.
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

const codeStyle: React.CSSProperties = {
  background: "#15171b",
  border: "1px solid #2a2d33",
  borderRadius: 4,
  padding: "1px 6px",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  fontSize: 13,
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