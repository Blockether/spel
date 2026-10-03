## DELETE http://localhost:42009/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 03 Oct 2026 13:29:00 GMT
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
curl 'http://localhost:42009/echo' \
  -X DELETE
```
