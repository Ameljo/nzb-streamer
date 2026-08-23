package org.nzbstreamer.controller;

import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.ArrayList;

@Controller
public class UiController {

    @Autowired
    private VirtualFileRepository virtualFileRepository;

    @GetMapping("/")
    public String index(Model model) {
        List<VirtualFile> files = new ArrayList<>();
        virtualFileRepository.findAll().forEach(files::add);
        model.addAttribute("files", files);
        return "index";
    }
}
