## DELETE http://localhost:33183/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 04 Aug 2026 22:11:59 GMT
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
curl 'http://localhost:33183/echo' \
  -X DELETE
```
