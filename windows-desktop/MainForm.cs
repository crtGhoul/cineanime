using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace CineAnime
{
    public class MainForm : Form
    {
        private WebView2? _webView;
        private static readonly string PrimaryUrl = "https://crtghoul.github.io/cineanime/";
        private static readonly string BackupMirrorUrl = "https://codeberg.org/crtGhoul/cineanime";

        public MainForm()
        {
            InitializeComponent();
            _ = InitializeWebViewAsync();
        }

        private void InitializeComponent()
        {
            this.Text = "CineAnime — Web Media Shell";
            this.BackColor = Color.FromArgb(9, 9, 11);
            this.ForeColor = Color.White;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.Size = new Size(1280, 800);
            this.MinimumSize = new Size(800, 600);

            // Set Form Icon if available
            try
            {
                string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
                if (File.Exists(iconPath))
                {
                    this.Icon = new Icon(iconPath);
                }
            }
            catch { }
        }

        private async Task InitializeWebViewAsync()
        {
            try
            {
                string userDataFolder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "CineAnime",
                    "WebView2Data"
                );

                var env = await CoreWebView2Environment.CreateAsync(null, userDataFolder);

                _webView = new WebView2
                {
                    Dock = DockStyle.Fill,
                    DefaultBackgroundColor = Color.FromArgb(9, 9, 11)
                };

                this.Controls.Add(_webView);

                await _webView.EnsureCoreWebView2Async(env);

                // Configure WebView settings for media player
                var settings = _webView.CoreWebView2.Settings;
                settings.IsStatusBarEnabled = false;
                settings.AreDefaultContextMenusEnabled = true;
                settings.IsWebMessageEnabled = true;

                // Open external links (Ko-fi, Codeberg, GitHub, donations) in the user's default desktop browser
                _webView.CoreWebView2.NewWindowRequested += (s, e) =>
                {
                    e.Handled = true;
                    if (!string.IsNullOrWhiteSpace(e.Uri) && (e.Uri.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || e.Uri.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
                    {
                        try
                        {
                            Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true });
                        }
                        catch { }
                    }
                };

                // Handle navigation failover
                _webView.CoreWebView2.NavigationCompleted += (s, e) =>
                {
                    if (!e.IsSuccess && e.WebErrorStatus != CoreWebView2WebErrorStatus.OperationCanceled)
                    {
                        var result = MessageBox.Show(
                            "Unable to reach primary CineAnime server. Would you like to switch to the backup mirror?",
                            "CineAnime Network Failover",
                            MessageBoxButtons.YesNo,
                            MessageBoxIcon.Warning
                        );

                        if (result == DialogResult.Yes && _webView.Source.ToString() != BackupMirrorUrl)
                        {
                            _webView.CoreWebView2.Navigate(BackupMirrorUrl);
                        }
                    }
                };

                _webView.Source = new Uri(PrimaryUrl);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    $"Error initializing CineAnime desktop core:\n{ex.Message}\n\nPlease ensure Microsoft Edge WebView2 Runtime is installed.",
                    "CineAnime Initialization Error",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
        }
    }
}
