## DELETE http://localhost:36577/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 03 Oct 2026 07:03:21 GMT
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
curl 'http://localhost:36577/echo' \
  -X DELETE
```
