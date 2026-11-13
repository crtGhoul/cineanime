using System;
using System.Collections.Generic;
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
        private readonly List<string> _mirrorUrls = new List<string>
        {
            "https://crtghoul.github.io/cineanime/",
            "https://velazquez-lab.pages.dev/cineanime/"
        };
        private int _mirrorIndex = 0;

        private Rectangle _normalWindowBounds;
        private FormBorderStyle _normalFormBorderStyle = FormBorderStyle.Sizable;
        private FormWindowState _normalWindowState = FormWindowState.Normal;
        private bool _isAppFullscreen = false;

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
            this.KeyPreview = true;

            try
            {
                string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app.ico");
                if (File.Exists(iconPath))
                {
                    this.Icon = new Icon(iconPath);
                }
            }
            catch { }

            this.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.F11)
                {
                    e.Handled = true;
                    ToggleFullScreen();
                }
                else if (e.KeyCode == Keys.Escape && _isAppFullscreen)
                {
                    e.Handled = true;
                    HandleFullscreenToggle(false);
                }
            };
        }

        private void HandleFullscreenToggle(bool fullscreen)
        {
            if (this.InvokeRequired)
            {
                this.BeginInvoke(new Action(() => HandleFullscreenToggle(fullscreen)));
                return;
            }

            if (fullscreen && !_isAppFullscreen)
            {
                _isAppFullscreen = true;
                _normalFormBorderStyle = this.FormBorderStyle;
                _normalWindowState = this.WindowState;
                _normalWindowBounds = this.Bounds;

                this.SuspendLayout();
                this.WindowState = FormWindowState.Normal;
                this.FormBorderStyle = FormBorderStyle.None;
                this.Bounds = Screen.FromControl(this).Bounds;
                this.ResumeLayout();
            }
            else if (!fullscreen && _isAppFullscreen)
            {
                _isAppFullscreen = false;
                this.SuspendLayout();
                this.FormBorderStyle = _normalFormBorderStyle;
                this.WindowState = _normalWindowState;
                if (_normalWindowState == FormWindowState.Normal)
                {
                    this.Bounds = _normalWindowBounds;
                }
                this.ResumeLayout();
            }
        }

        private void ToggleFullScreen()
        {
            HandleFullscreenToggle(!_isAppFullscreen);
        }

        private string? ResolveBundledOfflineFolder()
        {
            string baseDir = AppDomain.CurrentDomain.BaseDirectory;
            string wwwroot = Path.Combine(baseDir, "wwwroot");
            if (File.Exists(Path.Combine(wwwroot, "index.html")))
            {
                return wwwroot;
            }
            if (File.Exists(Path.Combine(baseDir, "index.html")))
            {
                return baseDir;
            }
            return null;
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

                _webView.KeyDown += (s, e) =>
                {
                    if (e.KeyCode == Keys.F11)
                    {
                        e.Handled = true;
                        ToggleFullScreen();
                    }
                    else if (e.KeyCode == Keys.Escape && _isAppFullscreen)
                    {
                        e.Handled = true;
                        HandleFullscreenToggle(false);
                    }
                };

                this.Controls.Add(_webView);

                await _webView.EnsureCoreWebView2Async(env);

                // Map local embedded bundle to a secure HTTPS virtual origin so crypto.subtle & HLS work offline forever
                string? offlineFolder = ResolveBundledOfflineFolder();
                if (!string.IsNullOrEmpty(offlineFolder))
                {
                    _webView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                        "offline.cineanime.local",
                        offlineFolder,
                        CoreWebView2HostResourceAccessKind.Allow
                    );
                    _mirrorUrls.Add("https://offline.cineanime.local/index.html");
                }

                var settings = _webView.CoreWebView2.Settings;
                settings.IsStatusBarEnabled = false;
                settings.AreDefaultContextMenusEnabled = true;
                settings.IsWebMessageEnabled = true;

                _webView.CoreWebView2.ContainsFullScreenElementChanged += (s, e) =>
                {
                    HandleFullscreenToggle(_webView.CoreWebView2.ContainsFullScreenElement);
                };

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

                // Automatic silent multi-mirror + embedded offline failover
                _webView.CoreWebView2.NavigationCompleted += (s, e) =>
                {
                    if (!e.IsSuccess
                        && e.WebErrorStatus != CoreWebView2WebErrorStatus.OperationCanceled
                        && e.WebErrorStatus != CoreWebView2WebErrorStatus.ConnectionAborted)
                    {
                        if (_mirrorIndex + 1 < _mirrorUrls.Count)
                        {
                            _mirrorIndex++;
                            _webView.CoreWebView2.Navigate(_mirrorUrls[_mirrorIndex]);
                            return;
                        }

                        var retry = MessageBox.Show(
                            "Unable to reach CineAnime mirrors. Check your internet connection and click Retry to try again.",
                            "CineAnime Network Failover",
                            MessageBoxButtons.RetryCancel,
                            MessageBoxIcon.Warning
                        );

                        if (retry == DialogResult.Retry)
                        {
                            _mirrorIndex = 0;
                            _webView.CoreWebView2.Navigate(_mirrorUrls[0]);
                        }
                    }
                };

                _mirrorIndex = 0;
                _webView.Source = new Uri(_mirrorUrls[0]);
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
