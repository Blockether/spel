## DELETE http://localhost:44403/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 22 Nov 2026 14:54:23 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:44403/echo' \
  -X DELETE
```
