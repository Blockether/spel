## DELETE http://localhost:35839/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 22 Nov 2026 13:21:09 GMT
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
curl 'http://localhost:35839/echo' \
  -X DELETE
```
