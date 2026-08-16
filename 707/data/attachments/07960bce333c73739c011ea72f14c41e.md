## DELETE http://localhost:42593/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 16 Aug 2026 15:47:42 GMT
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
curl 'http://localhost:42593/echo' \
  -X DELETE
```
