## DELETE http://localhost:37159/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 27 Sep 2026 17:53:58 GMT
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
curl 'http://localhost:37159/echo' \
  -X DELETE
```
